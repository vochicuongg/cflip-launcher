package com.cuong.cflip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.media.MediaControllerRepository
import com.cuong.cflip.media.MediaState
import kotlinx.coroutines.delay

private fun fmt(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/** Media tab (2.0): centred disc, big play button, mint seek bar. Polls the session every second. */
@Composable
fun MediaScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { MediaControllerRepository(context) }
    val media by MediaState.current.collectAsState()
    // Old bug: the slider value came straight from the 1-second poll, so while
    // the user dragged, the next poll snapped the thumb back to the live
    // position. While dragging, show the dragged value and seek only on release.
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) { repo.refresh(); delay(1000) }
    }

    Column(modifier = modifier.fillMaxSize().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Âm nhạc", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            color = CFlipColors.TextPrimary, modifier = Modifier.fillMaxWidth())
        val m = media
        if (m == null) {
            EmptyState(Icons.Filled.MusicNote, "Chưa có bài nào đang phát",
                "Mở YouTube Music / Spotify rồi quay lại")
        } else {
            Spacer(Modifier.height(18.dp))
            // Disc: mint gradient ring while playing, quiet glass when paused.
            Box(
                modifier = Modifier.size(148.dp).clip(CircleShape)
                    .background(
                        if (m.isPlaying) Brush.sweepGradient(
                            listOf(CFlipColors.Accent, Color(0xFF2E8C6B),
                                Color(0xFF7FE3C0), CFlipColors.Accent))
                        else Brush.linearGradient(
                            listOf(Color(0xFF2A3330), Color(0xFF171D1B)))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.size(52.dp).clip(CircleShape)
                    .background(CFlipColors.Bg), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null,
                        tint = if (m.isPlaying) CFlipColors.Accent else CFlipColors.TextSecondary,
                        modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(m.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = CFlipColors.TextPrimary, maxLines = 2, textAlign = TextAlign.Center)
            if (m.artist.isNotEmpty()) {
                Text(m.artist, fontSize = 13.sp, color = CFlipColors.TextSecondary,
                    maxLines = 1, textAlign = TextAlign.Center)
            }
            if (m.durationMs > 0) {
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = if (dragging) dragPos else m.positionMs.toFloat(),
                    onValueChange = { dragging = true; dragPos = it },
                    onValueChangeFinished = { dragging = false; repo.seekTo(dragPos.toLong()) },
                    valueRange = 0f..m.durationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = CFlipColors.Accent,
                        activeTrackColor = CFlipColors.Accent,
                        inactiveTrackColor = CFlipColors.CardStroke,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(fmt(m.positionMs), fontSize = 11.sp, color = CFlipColors.TextFaint)
                    Spacer(Modifier.weight(1f))
                    Text(fmt(m.durationMs), fontSize = 11.sp, color = CFlipColors.TextFaint)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                RoundControl(Icons.Filled.SkipPrevious, 52.dp, false) { repo.previous() }
                RoundControl(
                    if (m.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    72.dp, true,
                ) { repo.playPause() }
                RoundControl(Icons.Filled.SkipNext, 52.dp, false) { repo.next() }
            }
        }
    }
}

/** Circular media button: filled mint for the primary action, glass otherwise. */
@Composable
private fun RoundControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape)
            .background(if (primary) CFlipColors.Accent else CFlipColors.CardFill)
            .border(1.dp, CFlipColors.CardStroke, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null,
            tint = if (primary) CFlipColors.AccentDark else CFlipColors.TextPrimary,
            modifier = Modifier.size(size * 0.5f))
    }
}
