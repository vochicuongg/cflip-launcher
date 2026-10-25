package com.cuong.cflip.media

import android.media.session.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow

data class MediaInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
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
