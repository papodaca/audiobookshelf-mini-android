package io.github.miniaudiobookshelf.android.player

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action.orEmpty()
            Log.i(PlaybackService.AUTO_TAG, "car broadcast $action")
            when (action) {
                UiModeManager.ACTION_ENTER_CAR_MODE -> projectionSeen = true
                UiModeManager.ACTION_EXIT_CAR_MODE -> {
                    projectionSeen = true
                    notifyGone("exit_car_mode")
                }
            }
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

    fun start() {
        if (started) return
        started = true
        audioManager = context.getSystemService(AudioManager::class.java)
        val filter = IntentFilter().apply {
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        audioManager?.registerAudioDeviceCallback(audioDevices, handler)
        val uiMode = context.getSystemService(UiModeManager::class.java)
        if (uiMode?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_CAR) {
            projectionSeen = true
        }
    }

    fun markProjection() {
        projectionSeen = true
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        audioManager?.unregisterAudioDeviceCallback(audioDevices)
        audioManager = null
    }

    private fun notifyGone(reason: String) {
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
        private const val TYPE_BLE_HEADSET = 26
        private const val TYPE_BLE_SPEAKER = 27
    }
}
