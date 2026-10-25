package com.cuong.cflip.cover

import android.app.Presentation
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
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
import com.cuong.cflip.media.MediaControllerRepository
import com.cuong.cflip.service.NotificationState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The cover launcher rendered ON a secondary display (the real Flip cover when
 * the system allows it, or an emulator secondary display).
 * Plain Views on purpose: a Presentation has its own window/lifecycle, and
 * Views avoid the extra lifecycle plumbing Compose needs there. The Compose
 * version of the same experience is ui/CoverApp.kt.
 * Content: clock, favourite apps grid, recent apps, notification count, media controls.
 */
class CoverPresentation(context: Context, display: Display) : Presentation(context, display) {

    init {
        // Fix 25/10/2026: a Presentation built from an Activity context uses
        // that activity's window token. When the Flip is folded, MainActivity
        // on the inner screen stops and the token dies, so show() fails with
        // "Unable to add window ... token null is not valid". An overlay-type
        // window does not depend on the activity token, so it survives the
        // fold - at the cost of requiring SYSTEM_ALERT_WINDOW ("Hiển thị trên
        // app khác"), which CFlip already asks for on the main screen. If that
        // permission is missing, show() now fails with the much clearer
        // "permission denied for window type 2032" instead of a token error.
        // This still cannot beat a Samsung-level block on third-party cover
        // windows (One UI 9 Flex Window); that case stays a platform limit
        // and the fallback is CFlip's own overlay, never an external app.
        window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    private val mediaRepo = MediaControllerRepository(context)
    private val clockHandler = Handler(Looper.getMainLooper())
    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private val tickClock = object : Runnable {
        override fun run() {
            clockView?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            dateView?.text = SimpleDateFormat("EEE, dd/MM", Locale.getDefault()).format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    // Old bug: the clock was set once in onCreate and never updated, so the
    // cover showed a frozen time until the Presentation was recreated.
    override fun onStop() {
        clockHandler.removeCallbacks(tickClock)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val appRepo = AppRepository(context)
        val allApps = appRepo.loadLaunchableApps()
        val byPackage = allApps.associateBy { it.packageName }
        val favorites = FavoritesStore(context).getFavorites().mapNotNull { byPackage[it] }.take(8)
        val recents = RecentAppsRepository(context).recentPackages(4).mapNotNull { byPackage[it] }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFF000000.toInt())
        }
        val scroll = ScrollView(context); scroll.addView(root)

        clockView = TextView(context).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            textSize = 34f; setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(clockView)
        dateView = TextView(context).apply {
            text = SimpleDateFormat("EEE, dd/MM", Locale.getDefault()).format(Date())
            textSize = 13f; setTextColor(0xFFCCCCCC.toInt())
        }
        root.addView(dateView)
        clockHandler.post(tickClock)
        root.addView(TextView(context).apply {
            text = "CFlip Launcher · Thông báo: ${NotificationState.active.value.size}"
            textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
        })

        fun appButton(label: String, icon: Drawable?, pkg: String): Button {
            return Button(context).apply {
                text = label; textSize = 10f
                isAllCaps = false
                if (icon != null) {
                    val d = icon.constantState?.newDrawable()?.mutate() ?: icon
                    d.setBounds(0, 0, dp(40), dp(40))
                    setCompoundDrawables(null, d, null, null)
                }
                setOnClickListener { AppLauncher.launch(context, pkg, display.displayId) }
            }
        }

        if (favorites.isNotEmpty()) {
            root.addView(TextView(context).apply {
                text = "Yêu thích"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            })
            val grid = GridLayout(context).apply { columnCount = 4 }
            for (app in favorites) {
                grid.addView(appButton(app.label, app.icon, app.packageName),
                    GridLayout.LayoutParams().apply { width = dp(76); height = dp(84) })
            }
            root.addView(grid)
        }

        if (recents.isNotEmpty()) {
            root.addView(TextView(context).apply {
                text = "Gần đây"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            })
            for (app in recents) {
                root.addView(Button(context).apply {
                    text = app.label; isAllCaps = false
                    gravity = Gravity.START
                    setOnClickListener { AppLauncher.launch(context, app.packageName, display.displayId) }
                })
            }
        }

        // Media controls row
        mediaRepo.refresh()
        val mediaRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        mediaRow.addView(Button(context).apply { text = "Prev"
            setOnClickListener { mediaRepo.previous() } })
        mediaRow.addView(Button(context).apply { text = "Play/Pause"
            setOnClickListener { mediaRepo.playPause() } })
        mediaRow.addView(Button(context).apply { text = "Next"
            setOnClickListener { mediaRepo.next() } })
        root.addView(mediaRow)

        root.addView(Button(context).apply {
            text = "Đóng"
            setOnClickListener { dismiss() }
        })

        setContentView(scroll)

        // User-chosen cover rotation (Style: 0/90/180/270). Rotation pivots on the
        // view centre; for 90/270 the near-square cover needs a small shrink so
        // the rotated corners stay on screen.
        val rotation = com.cuong.cflip.data.SettingsStore(context).load().coverRotation
        if (rotation != 0) {
            scroll.post {
                scroll.rotation = rotation.toFloat()
                if (rotation % 180 != 0 && scroll.width > 0 && scroll.height > 0) {
                    val s = minOf(scroll.width, scroll.height).toFloat() /
                        maxOf(scroll.width, scroll.height).toFloat()
                    scroll.scaleX = s; scroll.scaleY = s
                }
            }
        }
    }
}
