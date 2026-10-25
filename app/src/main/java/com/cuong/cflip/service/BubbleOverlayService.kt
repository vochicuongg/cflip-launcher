package com.cuong.cflip.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cuong.cflip.MainActivity
import com.cuong.cflip.control.QuickControls
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.data.FavoritesStore
import com.cuong.cflip.display.AppLauncher
import com.cuong.cflip.media.MediaControllerRepository

/**
 * Floating bubble menu ("bong bong menu") - CFlip's own always-available
 * surface that does NOT depend on Good Lock / MultiStar.
 *
 * A draggable "C" bubble (TYPE_APPLICATION_OVERLAY); tapping it opens a
 * small panel: favourite apps, flashlight, media controls, lock, open CFlip.
 * Plain Views on purpose (overlay windows + Compose need extra lifecycle care).
 *
 * Where it can appear: 100% on the main screen. Whether Samsung also surfaces
 * overlay windows on the cover while folded is exactly what the fold test
 * with this build is meant to find out (watch Logcat tag CFlipBubble).
 */
class BubbleOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val mediaRepo by lazy { MediaControllerRepository(this) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted - bubble not shown")
            stopSelf(); return
        }
        showBubble()
        Log.i(TAG, "Bubble shown")
    }

    private fun overlayParams(w: Int, h: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    private fun showBubble() {
        val ctx = this
        val tv = TextView(ctx).apply {
            text = "C"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0B3D5C.toInt())
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
        }
        val params = overlayParams(dp(56), dp(56), dp(12), dp(140))
        bubbleParams = params

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) togglePanel(); true }
                else -> false
            }
        }
        bubble = tv
        runCatching { wm.addView(tv, params) }
            .onFailure { Log.w(TAG, "addView bubble failed", it) }
    }

    private fun togglePanel() {
        if (panel != null) { hidePanel(); return }
        val ctx = this
        val byPackage = AppRepository(ctx).loadLaunchableApps().associateBy { it.packageName }
        val favorites = FavoritesStore(ctx).getFavorites().mapNotNull { byPackage[it] }.take(6)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(0xF21A1A1A.toInt())
            }
        }
        root.addView(TextView(ctx).apply {
            text = "CFlip Menu"; textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(ctx).apply {
            text = "Thông báo đang chờ: ${NotificationState.active.value.size}"
            textSize = 12f; setTextColor(0xFFCCCCCC.toInt())
        })

        fun appButton(label: String, icon: Drawable?, pkg: String): Button = Button(ctx).apply {
            text = label; textSize = 9f; isAllCaps = false
            if (icon != null) {
                val d = icon.constantState?.newDrawable()?.mutate() ?: icon
                d.setBounds(0, 0, dp(34), dp(34))
                setCompoundDrawables(null, d, null, null)
            }
            setOnClickListener { AppLauncher.launch(ctx, pkg); hidePanel() }
        }
        if (favorites.isNotEmpty()) {
            val grid = GridLayout(ctx).apply { columnCount = 3 }
            for (app in favorites) {
                grid.addView(appButton(app.label, app.icon, app.packageName),
                    GridLayout.LayoutParams().apply { width = dp(72); height = dp(76) })
            }
            root.addView(grid)
        }

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(ctx).apply {
            text = if (QuickControls.torchOn) "Đèn: Bật" else "Đèn pin"
            setOnClickListener {
                val next = !QuickControls.torchOn
                if (QuickControls.setTorch(ctx, next)) text = if (next) "Đèn: Bật" else "Đèn pin"
            }
        })
        row.addView(Button(ctx).apply {
            text = "Play/Pause"
            setOnClickListener { mediaRepo.refresh(); mediaRepo.playPause() }
        })
        row.addView(Button(ctx).apply {
            text = "Next"
            setOnClickListener { mediaRepo.refresh(); mediaRepo.next() }
        })
        root.addView(row)

        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(Button(ctx).apply {
            text = "Khoa may"
            setOnClickListener {
                // Old bug: a null AccessibilityService made this button do
                // nothing with no feedback. Tell the user instead.
                if (MyAccessibilityService.instance?.lockScreen() != true) {
                    android.widget.Toast.makeText(ctx, "Can bat Accessibility de khoa may", android.widget.Toast.LENGTH_SHORT).show()
                }
                hidePanel()
            }
        })
        row2.addView(Button(ctx).apply {
            text = "Mo CFlip"
            setOnClickListener {
                startActivity(Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                hidePanel()
            }
        })
        row2.addView(Button(ctx).apply {
            text = "An bong"
            setOnClickListener { stopSelf() }
        })
        root.addView(row2)

        val bp = bubbleParams
        // Old bug: with the bubble near the right edge, the 252dp panel ran
        // off-screen. Clamp its left edge to the visible width.
        val screenW = resources.displayMetrics.widthPixels
        val panelX = ((bp?.x ?: dp(12))).coerceIn(0, maxOf(0, screenW - dp(252)))
        val params = overlayParams(dp(252), WindowManager.LayoutParams.WRAP_CONTENT,
            panelX, (bp?.y ?: dp(140)) + dp(64))
        panel = root
        runCatching { wm.addView(root, params) }
            .onFailure { Log.w(TAG, "addView panel failed", it); panel = null }
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    override fun onDestroy() {
        hidePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        Log.i(TAG, "Bubble removed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CFlipBubble"

        fun start(context: Context) {
            runCatching { context.startService(Intent(context, BubbleOverlayService::class.java)) }
                .onFailure { Log.w(TAG, "start failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BubbleOverlayService::class.java)) }
        }
    }
}
