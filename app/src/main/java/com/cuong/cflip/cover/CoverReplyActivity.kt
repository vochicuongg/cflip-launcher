package com.cuong.cflip.cover

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * vc212 REPLY CARD (LiveBridge-style): a Now Bar-like card raised near
 * the top of the cover display - app + message context on top, a
 * rounded input field, Cancel / Send. It is a real Activity (not a
 * panel overlay) so the keyboard can appear on the cover display.
 * Send posts the typed text through the notification's RemoteInput,
 * exactly like the shade's inline reply.
 */
class CoverReplyActivity : ComponentActivity() {

    private val cardColor = 0xFF16181C.toInt()
    private val fieldColor = 0xFF23262B.toInt()
    private val mint = 0xFF7FE3C0.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.5f }
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        showReply(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showReply(intent)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun showReply(intent: Intent) {
        val key = intent.getStringExtra("cflip_reply_key").orEmpty()
        if (key.isBlank()) { finish(); return }
        val pkg = intent.getStringExtra("cflip_reply_pkg").orEmpty()
        val appLabel = intent.getStringExtra("cflip_reply_app").orEmpty()
        val title = intent.getStringExtra("cflip_reply_title").orEmpty()
        val text = intent.getStringExtra("cflip_reply_text").orEmpty()

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardColor, 20)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
        }
        // App + title row.
        val appRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
            ?: getDrawable(android.R.drawable.sym_def_app_icon)
        appRow.addView(ImageView(this).apply {
            setImageDrawable(icon)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) }
        })
        val appTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appTexts.addView(TextView(this).apply {
            this.text = appLabel.ifBlank { pkg.substringAfterLast('.') }
            textSize = 13f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        if (title.isNotBlank()) {
            appTexts.addView(TextView(this).apply {
                this.text = title; textSize = 12f; setTextColor(Color.WHITE)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        appRow.addView(appTexts)
        card.addView(appRow)
        if (text.isNotBlank()) {
            card.addView(TextView(this).apply {
                this.text = text; textSize = 12f; setTextColor(0xFFBBBBBB.toInt())
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            })
        }
        // Input field.
        val input = EditText(this).apply {
            hint = "Nhắn tin..."
            setHintTextColor(0xFF888888.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            background = rounded(fieldColor, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            minLines = 1; maxLines = 4
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
        card.addView(input)
        // Cancel / Send row.
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
        val cancel = Button(this).apply {
            this.text = "Hủy"; textSize = 13f; setTextColor(Color.WHITE)
            background = rounded(fieldColor, 24)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        }
        val send = Button(this).apply {
            this.text = "Gửi"; textSize = 13f; setTextColor(Color.BLACK)
            background = rounded(mint, 24)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
            isEnabled = false; alpha = 0.5f
            setOnClickListener {
                val msg = input.text.toString()
                if (msg.isNotBlank()) {
                    com.cuong.cflip.service.NotificationController.reply(key, msg)
                }
                finish()
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val ok = !s.isNullOrBlank()
                send.isEnabled = ok; send.alpha = if (ok) 1f else 0.5f
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send.performClick(); true } else false
        }
        btnRow.addView(cancel); btnRow.addView(send)
        card.addView(btnRow)
        root.addView(card)
        // Tapping outside the card cancels, like dismissing a sheet.
        root.setOnClickListener { finish() }
        card.setOnClickListener { /* swallow: taps on the card must not cancel */ }
        setContentView(root)
        input.requestFocus()
        root.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }
}
