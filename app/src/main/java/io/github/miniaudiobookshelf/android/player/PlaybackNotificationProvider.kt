@file:OptIn(UnstableApi::class)

package io.github.miniaudiobookshelf.android.player

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import io.github.miniaudiobookshelf.android.R

class PlaybackNotificationProvider(
    context: Context,
) : DefaultMediaNotificationProvider(
    context,
    { DEFAULT_NOTIFICATION_ID },
    CHANNEL_ID,
    R.string.playback_channel,
) {
    private val appContext = context.applicationContext

    init {
        setSmallIcon(R.drawable.ic_stat_playback)
    }

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        val playPause = if (showPauseButton) {
            CommandButton.Builder(CommandButton.ICON_PAUSE)
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                .setDisplayName(appContext.getString(R.string.pause))
                .setSlots(CommandButton.SLOT_CENTRAL)
                .build()
        } else {
            CommandButton.Builder(CommandButton.ICON_PLAY)
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                .setDisplayName(appContext.getString(R.string.play))
                .setSlots(CommandButton.SLOT_CENTRAL)
                .build()
        }
        return ImmutableList.of(
            skipButton(
                icon = CommandButton.ICON_SKIP_BACK_10,
                action = PlaybackService.ACTION_SKIP_BACK_10,
                name = R.string.skip_back_10,
                slot = CommandButton.SLOT_BACK,
            ),
            playPause,
            skipButton(
                icon = CommandButton.ICON_SKIP_FORWARD_10,
                action = PlaybackService.ACTION_SKIP_FORWARD_10,
                name = R.string.skip_forward_10,
                slot = CommandButton.SLOT_FORWARD,
            ),
        )
    }

    private fun skipButton(icon: Int, action: String, name: Int, slot: Int): CommandButton {
        return CommandButton.Builder(icon)
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            .setDisplayName(appContext.getString(name))
            .setSlots(slot)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "mabs_playback"
    }
}
