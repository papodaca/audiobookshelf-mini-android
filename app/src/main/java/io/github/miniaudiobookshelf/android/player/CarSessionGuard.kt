package io.github.miniaudiobookshelf.android.player

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

class CarSessionGuard(
    private val context: Context,
    private val onCarDisconnected: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var projectionSeen = false
    private var started = false
    private var projectionAtElapsed = 0L
    private val pendingGone = Runnable { notifyGone("connection_idle") }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action.orEmpty()
            Log.i(PlaybackService.AUTO_TAG, "car broadcast $action")
            when (action) {
                UiModeManager.ACTION_ENTER_CAR_MODE -> markProjection()
                UiModeManager.ACTION_EXIT_CAR_MODE -> notifyGone("exit_car_mode")
                ACTION_CAR_CONNECTION_UPDATED -> checkProvider("broadcast")
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    if (projectionSeen) notifyGone("audio_noisy")
                }
            }
        }
    }

    private val connectionObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            checkProvider("provider")
        }
    }

    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (!projectionSeen) return
            val car = removedDevices.filter { isCarAudio(it.type) }
            if (car.isEmpty()) return
            Log.i(
                PlaybackService.AUTO_TAG,
                "car audio gone types=${car.joinToString { it.type.toString() }}",
            )
            notifyGone("audio_device")
        }
    }

    private val modePoll = object : Runnable {
        override fun run() {
            if (!started) return
            val uiMode = context.getSystemService(UiModeManager::class.java)
            val mode = uiMode?.currentModeType
            val type = readConnectionType()
            if (projectionSeen) {
                Log.i(PlaybackService.AUTO_TAG, "car poll uimode=$mode connection=$type")
                if (mode != null && mode != Configuration.UI_MODE_TYPE_CAR && type == CONNECTION_NONE) {
                    notifyGone("uimode_and_connection")
                }
            }
            handler.postDelayed(this, 2_000)
        }
    }

    fun start() {
        if (started) return
        started = true
        audioManager = context.getSystemService(AudioManager::class.java)
        val filter = IntentFilter().apply {
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
            addAction(ACTION_CAR_CONNECTION_UPDATED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try {
            context.contentResolver.registerContentObserver(CONNECTION_URI, true, connectionObserver)
        } catch (e: Exception) {
            Log.w(PlaybackService.AUTO_TAG, "car connection observer failed", e)
        }
        audioManager?.registerAudioDeviceCallback(audioDevices, handler)
        val uiMode = context.getSystemService(UiModeManager::class.java)
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_CAR) {
            markProjection()
        }
        checkProvider("start")
        handler.post(modePoll)
    }

    fun markProjection() {
        handler.removeCallbacks(pendingGone)
        projectionSeen = true
        projectionAtElapsed = SystemClock.elapsedRealtime()
        Log.i(PlaybackService.AUTO_TAG, "car projection seen")
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(pendingGone)
        handler.removeCallbacks(modePoll)
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        try {
            context.contentResolver.unregisterContentObserver(connectionObserver)
        } catch (_: Exception) {
        }
        audioManager?.unregisterAudioDeviceCallback(audioDevices)
        audioManager = null
    }

    private fun checkProvider(reason: String) {
        val type = readConnectionType()
        Log.i(PlaybackService.AUTO_TAG, "car connection reason=$reason type=$type seen=$projectionSeen")
        when (type) {
            CONNECTION_PROJECTION, CONNECTION_NATIVE -> markProjection()
            CONNECTION_NONE -> scheduleGone()
        }
    }

    private fun scheduleGone() {
        if (!projectionSeen) return
        val held = SystemClock.elapsedRealtime() - projectionAtElapsed
        if (held < CONNECT_GRACE_MS) {
            Log.i(PlaybackService.AUTO_TAG, "car idle ignored, connected for ${held}ms")
            return
        }
        handler.removeCallbacks(pendingGone)
        handler.postDelayed(pendingGone, IDLE_DEBOUNCE_MS)
    }

    private fun readConnectionType(): Int? {
        return try {
            context.contentResolver.query(
                CONNECTION_URI,
                arrayOf(CONNECTION_STATE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(CONNECTION_STATE)
                if (index < 0) null else cursor.getInt(index)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun notifyGone(reason: String) {
        handler.removeCallbacks(pendingGone)
        if (!projectionSeen) {
            Log.i(PlaybackService.AUTO_TAG, "car gone ignored reason=$reason")
            return
        }
        projectionSeen = false
        Log.i(PlaybackService.AUTO_TAG, "car gone reason=$reason")
        onCarDisconnected()
    }

    private fun isCarAudio(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BUS ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_HDMI ||
            type == AudioDeviceInfo.TYPE_DOCK ||
            type == AudioDeviceInfo.TYPE_AUX_LINE ||
            type == TYPE_BLE_HEADSET ||
            type == TYPE_BLE_SPEAKER
    }

    companion object {
        private val CONNECTION_URI: Uri = Uri.parse("content://androidx.car.app.connection")
        private const val CONNECTION_STATE = "CarConnectionState"
        private const val CONNECTION_NONE = 0
        private const val CONNECTION_NATIVE = 1
        private const val CONNECTION_PROJECTION = 2
        private const val ACTION_CAR_CONNECTION_UPDATED =
            "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
        private const val TYPE_BLE_HEADSET = 26
        private const val TYPE_BLE_SPEAKER = 27
        private const val CONNECT_GRACE_MS = 8_000L
        private const val IDLE_DEBOUNCE_MS = 1_500L
    }
}
