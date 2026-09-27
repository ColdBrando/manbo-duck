package com.fanduck.agent

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

/**
 * 打字输入条：键盘代替"说一句"。发送后喂给 `onTyped`，走的就是 §4.2 那条链
 * （拼意图 → 云端 → dispatch → did 落盘），鸭子照样用 TTS 出声回答。
 *
 * 事件记成 `Kind.TEXT`、意图里写「打字：xxx」—— 起初是按 `heard` 记的（模型会以为这句话
 * 是说出来的），评审 P2 之后分了单独一类，说话那条链一个字都没动。
 *
 * 和 `DuckPanel` 一样只挂 debug 构建（§6.5 的屏幕是一整块 WebView，没有控件）。
 * 每次发送往 logcat 打一条 `duck-input`。
 */
class TextInputBar(
    private val activity: Activity,
    private val onSend: (String) -> Unit,
) {

    private val field = EditText(activity).apply {
        hint = "说点什么"
        textSize = 14f
        maxLines = 1
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEND
        setTextColor(TEXT)
        setHintTextColor(HINT)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(FIELD_BG)
    }

    private val send = Button(activity).apply {
        text = "发送"
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(16), dp(4), dp(16), dp(4))
        setTextColor(TEXT)
        background = rounded(BUTTON_BG)
    }

    val view: View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = rounded(BAR_BG)
        addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            send,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) },
        )
    }

    init {
        send.setOnClickListener { submit() }
        // 软键盘的回车走 IME_ACTION_SEND；外接键盘的回车是普通 Enter，一起收
        field.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                submit()
                true
            } else {
                false
            }
        }
    }

    private fun submit() {
        val text = field.text.toString().trim()
        if (text.isEmpty()) return
        Log.i(TAG, "text: $text")
        field.setText("")
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(field.windowToken, 0)
        onSend(text)
    }

    private fun rounded(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        activity.resources.displayMetrics,
    ).toInt()

    private companion object {
        const val TAG = "duck-input"
        const val BAR_BG = 0xE60B0F14.toInt()
        const val FIELD_BG = 0x33FFFFFF
        const val BUTTON_BG = 0x33FFFFFF
        const val TEXT = 0xFFE8EEF7.toInt()
        const val HINT = 0x8AE8EEF7.toInt()
    }
}
