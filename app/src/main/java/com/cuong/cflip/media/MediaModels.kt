package com.cuong.cflip.media

import android.app.PendingIntent
import android.graphics.Bitmap

import android.media.session.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow

data class MediaInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val albumArt: Bitmap? = null,
    // vc41: MediaSession's own destination for this track/app
    // (sessionActivity PendingIntent), if the app provides one.
    // Null for apps that do not expose it - then the panel falls
    // back to launching the app package on the cover display.
    val sessionActivity: PendingIntent? = null,
)

object MediaState {
    val current = MutableStateFlow<MediaInfo?>(null)
}

fun playbackStateName(state: Int): String = when (state) {
    PlaybackState.STATE_PLAYING -> "playing"
    PlaybackState.STATE_PAUSED -> "paused"
    PlaybackState.STATE_BUFFERING -> "buffering"
    else -> "other"
}
