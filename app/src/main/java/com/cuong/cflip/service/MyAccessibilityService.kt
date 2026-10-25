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
    // vc42/vc44: cover screenshot via takeScreenshot(displayId)
    // (API 30+, minSdk 33 here), saved to Gallery by CFlip itself.
    // vc44 ROOT CAUSE found: vc42's takeScreenshot ALWAYS failed
    // because this service never declared canTakeScreenshot in its
    // meta-data (accessibility_service_config.xml) - Android docs
    // require that declaration, otherwise the call fails with
    // NO_ACCESSIBILITY_ACCESS (code 2). The panel then fell back
    // to GLOBAL_ACTION_TAKE_SCREENSHOT, which targets the DEFAULT
    // (main) display, so folded it captured the main display and
    // looked like "chụp màn chính, không phải màn phụ". vc44 adds
    // canTakeScreenshot=true to the config AND removes the silent
    // main-display fallback: if the cover API fails, the panel
    // reports the real error code instead of capturing the wrong
    // display. Note: after this config change the service must
    // re-bind - if the first screenshot after update still reports
    // code 2, toggle CFlip Accessibility off/on once.
    // Error codes (Android): 1 internal, 2 no screenshot access,
    // 3 too soon after previous shot (1s interval), 4 invalid
    // display, 5 invalid window, 6 secure window.
    fun takeScreenshotForDisplay(displayId: Int, onDone: (Boolean, String) -> Unit) {
        runCatching {
            takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
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
                    val why = when (errorCode) {
                        1 -> "lỗi nội bộ hệ thống"
                        2 -> "chưa có quyền chụp (tắt/bật lại Accessibility cho CFlip một lần để nhận cấu hình mới)"
                        3 -> "chụp quá nhanh sau lần trước (cách nhau ít nhất 1 giây)"
                        4 -> "display màn phụ không hợp lệ với API chụp của hệ thống"
                        5 -> "cửa sổ không hợp lệ"
                        6 -> "nội dung bảo mật, hệ thống cấm chụp"
                        else -> "lỗi không rõ"
                    }
                    onDone(false, "mã $errorCode: $why")
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
