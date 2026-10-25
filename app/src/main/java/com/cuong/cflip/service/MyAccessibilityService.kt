package com.cuong.cflip.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * System actions + interaction hooks for CFlip.
 * The static [instance] lets the UI trigger real system actions that only an
 * accessibility service can perform: lock screen, recents, notifications
 * shade and quick settings. Keep the scope minimal - this is a personal build.
 */
class MyAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "Window: ${event.packageName}/${event.className}")
        }
    }

    override fun onInterrupt() { Log.i(TAG, "Interrupted") }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    // vc41: system screenshot via Accessibility (API 28+, minSdk 33 here).
    // This is the supported route for a third-party app - it triggers
    // the system screenshot UI, no MediaProjection needed. The panel
    // hides first, so the shot captures what is underneath, not the panel.
    fun takeScreenshot(): Boolean = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    // vc42: cover screenshot via takeScreenshot(displayId) (API 30+,
    // minSdk 33 here), saved to Gallery by CFlip itself. Why this
    // exists: vc41's GLOBAL_ACTION_TAKE_SCREENSHOT targets the
    // DEFAULT display, so while folded it captures (or fails on)
    // the main display the user cannot see, and looked like
    // "không hoạt động" on the cover. This API takes a displayId,
    // so the cover display (display 1) can be captured while folded.
    // On success the bitmap is saved as PNG to Pictures/Screenshots
    // via MediaStore. On failure the caller falls back to the global
    // action above. Callback runs on the main executor.
    fun takeScreenshotForDisplay(displayId: Int, onDone: (Boolean, String) -> Unit) {
        runCatching {
            takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback() {
                override fun onSuccess(result: ScreenshotResult) {
                    runCatching {
                        val buf = result.hardwareBuffer
                        val raw = android.graphics.Bitmap.wrapHardwareBuffer(buf, result.colorSpace)
                            ?: throw IllegalStateException("No bitmap from screenshot")
                        val copy = raw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        runCatching { buf.close() }
                        val name = "CFlip_" + System.currentTimeMillis() + ".png"
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
                        }
                        val uri = contentResolver.insert(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("MediaStore insert failed")
                        contentResolver.openOutputStream(uri)?.use { out ->
                            copy.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        } ?: throw IllegalStateException("Cannot open output stream")
                        onDone(true, name)
                    }.onFailure { e ->
                        Log.w(TAG, "Save cover screenshot failed", e)
                        onDone(false, e.message ?: "save failed")
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Cover screenshot failed: $errorCode")
                    onDone(false, "error $errorCode")
                }
            })
        }.onFailure { e ->
            Log.w(TAG, "takeScreenshot(display) threw", e)
            onDone(false, e.message ?: "throw")
        }
    }
    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    companion object {
        private const val TAG = "CFlipA11y"
        @Volatile var instance: MyAccessibilityService? = null
            private set
    }
}
