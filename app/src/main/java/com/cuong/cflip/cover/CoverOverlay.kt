package com.cuong.cflip.cover

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.data.FavoritesStore
import com.cuong.cflip.data.RecentAppsRepository
import com.cuong.cflip.display.AppLauncher
import com.cuong.cflip.service.NotificationState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cover UI drawn as a plain overlay View on the secondary display, instead
 * of as a Presentation (Dialog).
 *
 * Why this exists (25/10/2026): Presentation kept failing on the Flip5 with
 * "Unable to add window" even after switching it to an overlay window type
 * in 2.3.1. A Presentation is a Dialog, and Samsung's Flex Window appears
 * to refuse Dialog windows from third-party apps on the cover display.
 * The route 9 Flip itself uses is different: take the display's own
 * WindowManager via createDisplayContext(display) and addView() a plain
 * View with TYPE_APPLICATION_OVERLAY on it. That is not a Dialog, it needs
 * no activity token, and it is exactly what CFlip's bubble already does
 * successfully on the main display - only here the WindowManager belongs
 * to the secondary display.
 *
 * This does NOT replace Presentation: the service and the main-screen
 * button try Presentation first and fall back to this only if it throws.
 * Whichever path succeeds is the one the user will see, and the log tag
 * CFlipOverlay records which one it was.
 */
object CoverOverlayManager {
    private const val TAG = "CFlipOverlay"
    private var wm: WindowManager? = null
    private var view: View? = null
    private var clockHandler: Handler? = null
    private var clockRunnable: Runnable? = null

    fun isShown(): Boolean = view != null

    fun show(appContext: Context, display: Display): Result<Unit> = runCatching {
        hide()
        // The WindowManager must come from the display context, otherwise
        // the view lands on the default (inner) display, not the cover.
        val displayContext = appContext.createDisplayContext(display)
        val manager = displayContext.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("No WindowManager for display ${display.displayId}")
        val root = buildView(displayContext, display)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        manager.addView(root, params)
        wm = manager
        view = root
        Log.i(TAG, "Overlay shown on display ${display.displayId}")
    }.onFailure { Log.w(TAG, "Overlay addView failed on display ${display.displayId}", it) }

    fun hide() {
        clockRunnable?.let { clockHandler?.removeCallbacks(it) }
        clockRunnable = null
        clockHandler = null
        val v = view
        if (v != null) {
            runCatching { wm?.removeView(v) }
            view = null
            wm = null
            Log.i(TAG, "Overlay hidden")
        }
    }

    private fun buildView(ctx: Context, display: Display): View {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val appRepo = AppRepository(ctx)
        val allApps = appRepo.loadLaunchableApps()
        val byPackage = allApps.associateBy { it.packageName }
        val favorites = FavoritesStore(ctx).getFavorites().mapNotNull { byPackage[it] }.take(8)
        val recents = RecentAppsRepository(ctx).recentPackages(4).mapNotNull { byPackage[it] }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFF000000.toInt())
        }
        val scroll = ScrollView(ctx); scroll.addView(root)

        val clockView = TextView(ctx).apply {
            textSize = 34f; setTextColor(0xFFFFFFFF.toInt())
        }
        val dateView = TextView(ctx).apply {
            textSize = 13f; setTextColor(0xFFCCCCCC.toInt())
        }
        root.addView(clockView)
        root.addView(dateView)
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                dateView.text = SimpleDateFormat("EEE, dd/MM", Locale.getDefault()).format(Date())
                handler.postDelayed(this, 1000)
            }
        }
        clockHandler = handler
        clockRunnable = tick
        handler.post(tick)

        root.addView(TextView(ctx).apply {
            text = "CFlip Launcher · Thông báo: ${NotificationState.active.value.size}"
            textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
        })

        fun appButton(label: String, icon: Drawable?, pkg: String): Button {
            return Button(ctx).apply {
                text = label; textSize = 10f; isAllCaps = false
                if (icon != null) {
                    val d = icon.constantState?.newDrawable()?.mutate() ?: icon
                    d.setBounds(0, 0, dp(40), dp(40))
                    setCompoundDrawables(null, d, null, null)
                }
                setOnClickListener { AppLauncher.launch(ctx, pkg, display.displayId) }
            }
        }

        if (favorites.isNotEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "Yêu thích"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            })
            val grid = GridLayout(ctx).apply { columnCount = 4 }
            for (app in favorites) {
                grid.addView(appButton(app.label, app.icon, app.packageName),
                    GridLayout.LayoutParams().apply { width = dp(76); height = dp(84) })
            }
            root.addView(grid)
        }
        if (recents.isNotEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "Gần đây"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            })
            for (app in recents) {
                root.addView(Button(ctx).apply {
                    text = app.label; isAllCaps = false; gravity = Gravity.START
                    setOnClickListener { AppLauncher.launch(ctx, app.packageName, display.displayId) }
                })
            }
        }
        root.addView(Button(ctx).apply {
            text = "Đóng"
            setOnClickListener { hide() }
        })
        return scroll
    }
}
