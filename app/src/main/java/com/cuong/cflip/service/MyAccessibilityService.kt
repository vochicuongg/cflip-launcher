package com.cuong.cflip.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cuong.cflip.CFlipApp
import com.cuong.cflip.R
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
    // vc47: soft system-style click, generated in code.
    // 80ms sine burst at 2000Hz, decaying envelope, amplitude
    // capped at 20% and scaled to current STREAM_SYSTEM volume.
    // This is deliberately NOT MediaActionSound (vc45 shutter,
    // fixed-volume and too loud) and NOT FX_KEY_CLICK (vc46,
    // silent on this Samsung). Silent on silent/vibrate.
    private fun playSoftClick() {
        runCatching {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE)
                as? android.media.AudioManager ?: return@runCatching
            if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return@runCatching
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_SYSTEM).coerceAtLeast(1)
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_SYSTEM)
            val frac = (cur.toFloat() / max).coerceIn(0f, 1f)
            if (frac <= 0f) return@runCatching
            val sampleRate = 44100
            val numSamples = sampleRate * 80 / 1000
            val amplitude = (frac * 0.20f * Short.MAX_VALUE).toInt()
            val samples = ShortArray(numSamples) { i ->
                val env = 1f - i.toFloat() / numSamples
                (amplitude * env * kotlin.math.sin(2 * Math.PI * 2000 * i / sampleRate)).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ runCatching { track.stop(); track.release() } }, 150)
        }
    }

    fun takeScreenshotForDisplay(displayId: Int, onDone: (Boolean, String) -> Unit) {
        takeScreenshotForDisplayInner(displayId, onDone, retried = false)
    }

    // vc47 watchdog + retry: the system is supposed to always
    // invoke onSuccess/onFailure, but vc46's symptom (first shot
    // toasts, later shots silent) shows a later callback can
    // silently never arrive - if no callback fires within 3s,
    // report a timeout instead of staying silent forever. And
    // code 3 (shot too soon after previous, 1s interval) is now
    // retried once after 1200ms instead of failing the user.
    private fun takeScreenshotForDisplayInner(displayId: Int, onDone: (Boolean, String) -> Unit, retried: Boolean) {
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val doneOnce: (Boolean, String) -> Unit = { ok, msg ->
            if (fired.compareAndSet(false, true)) onDone(ok, msg)
        }
        val watchdog = android.os.Handler(android.os.Looper.getMainLooper())
        watchdog.postDelayed({
            if (!fired.get()) {
                Log.w(TAG, "Cover screenshot callback never arrived (watchdog)")
                doneOnce(false, "hệ thống không trả kết quả chụp trong 3 giây (thử lại lần nữa)")
            }
        }, 3000)
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
                        playSoftClick()
                        runCatching {
                            val notif = NotificationCompat.Builder(this@MyAccessibilityService, CFlipApp.CHANNEL_ID)
                                .setContentTitle("CFlip - Đã chụp màn phụ")
                                .setContentText(name)
                                .setSmallIcon(R.drawable.ic_launcher_foreground)
                                .setAutoCancel(true)
                                .build()
                            getSystemService(NotificationManager::class.java)
                                ?.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
                        }
                        doneOnce(true, name)
                    }.onFailure { e ->
                        Log.w(TAG, "Save cover screenshot failed", e)
                        doneOnce(false, e.message ?: "save failed")
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Cover screenshot failed: $errorCode")
                    if (errorCode == 3 && !retried) {
                        // Interval too short: retry once after 1200ms
                        // (past the 1s system interval), do not
                        // report failure to the user yet.
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({
                                takeScreenshotForDisplayInner(displayId, onDone, retried = true)
                            }, 1200)
                        return
                    }
                    val why = when (errorCode) {
                        1 -> "lỗi nội bộ hệ thống"
                        2 -> "chưa có quyền chụp (tắt/bật lại Accessibility cho CFlip một lần để nhận cấu hình mới)"
                        3 -> "chụp quá nhanh sau lần trước (cách nhau ít nhất 1 giây)"
                        4 -> "display màn phụ không hợp lệ với API chụp của hệ thống"
                        5 -> "cửa sổ không hợp lệ"
                        6 -> "nội dung bảo mật, hệ thống cấm chụp"
                        else -> "lỗi không rõ"
                    }
                    doneOnce(false, "mã $errorCode: $why")
                }
            })
        }.onFailure { e ->
            Log.w(TAG, "takeScreenshot(display) threw", e)
            doneOnce(false, e.message ?: "throw")
        }
    }
    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    companion object {
        private const val TAG = "CFlipA11y"
        @Volatile var instance: MyAccessibilityService? = null
            private set
    }
}
