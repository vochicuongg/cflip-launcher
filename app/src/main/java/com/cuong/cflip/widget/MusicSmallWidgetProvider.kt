package com.cuong.cflip.widget

import com.cuong.cflip.R

/**
 * vc246 CASSETTE SMALL (the reference app: Cassette Small / Small Tape) — the compact
 * music widget. It reuses MusicWidgetProvider's whole code path: same
 * onUpdate/onReceive, same action PendingIntents (broadcasts target
 * javaClass, so they land back on this subclass), same ids. Only the
 * layout differs (widget_music_small): title + artist, tape window,
 * prev / big-mint play-pause / next. Ids absent from the small layout
 * (refresh, state eyebrow, progress) are no-ops in RemoteViews.
 * Registered in the manifest + xml like the other 4 widgets.
 */
class MusicSmallWidgetProvider : MusicWidgetProvider() {
    override val layoutRes: Int = R.layout.widget_music_small
}
