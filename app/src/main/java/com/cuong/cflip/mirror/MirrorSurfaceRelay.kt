package com.cuong.cflip.mirror

/**
 * vc187 ACTIVITY PORT: static handoff between ScreenMirrorService
 * (session owner: dual, projection, virtual display) and
 * MirrorActivity (the display-1 window). The service fills the
 * session params and callbacks BEFORE launching the Activity;
 * the Activity binds them in onCreate and reports back through
 * them. Kept deliberately boring: no threads, no queues, all
 * touched on the main thread like the Presentation callbacks
 * it replaces.
 */
object MirrorSurfaceRelay {
    var activity: MirrorActivity? = null
    var sourceW: Int = 0
    var sourceH: Int = 0
    var windowOrientation: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    var contentDeg: Int = 0
    // True while a stop is intentional (cleanup, surface timeout),
    // so MirrorActivity.onDestroy does not trigger the safety net.
    var intentionalStop: Boolean = false
    var onSurfaceReady: ((android.view.Surface) -> Unit)? = null
    var onSurfaceGone: (() -> Unit)? = null
    var onControl: ((String) -> Unit)? = null
    // Fired from MirrorActivity.onDestroy for an unintentional
    // stop only; the service answers with cleanup (bubble back),
    // never with a re-show loop.
    var onDestroyed: (() -> Unit)? = null

    fun clear() {
        activity = null
        onSurfaceReady = null
        onSurfaceGone = null
        onControl = null
        onDestroyed = null
        intentionalStop = false
    }
}
