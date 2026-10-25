package com.cuong.cflip.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import com.cuong.cflip.service.MyNotificationListenerService

/**
 * Full media controls for the cover screen.
 * getActiveSessions() requires Notification Access - without it, refresh()
 * simply leaves MediaState empty instead of crashing.
 */
class MediaControllerRepository(private val context: Context) {

    private fun controller(): MediaController? {
        val msm = context.getSystemService(android.media.session.MediaSessionManager::class.java)
            ?: return null
        val listener = ComponentName(context, MyNotificationListenerService::class.java)
        val sessions = runCatching { msm.getActiveSessions(listener) }.getOrNull() ?: return null
        // Prefer a playing session, else the most recent one
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }

    fun refresh() {
        val c = controller()
        if (c == null) { MediaState.current.value = null; return }
        val md = c.metadata
        MediaState.current.value = MediaInfo(
            packageName = c.packageName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown title",
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            isPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            positionMs = c.playbackState?.position ?: 0L,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
        )
    }

    fun playPause() {
        val c = controller() ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
        refresh()
    }

    fun next() { controller()?.transportControls?.skipToNext(); refresh() }
    fun previous() { controller()?.transportControls?.skipToPrevious(); refresh() }
    fun seekTo(positionMs: Long) { controller()?.transportControls?.seekTo(positionMs); refresh() }
}
