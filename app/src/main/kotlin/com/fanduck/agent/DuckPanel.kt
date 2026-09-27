package com.fanduck.agent

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/**
 * 调试面板：一列按钮直接驱动 `RobotPort`，用来核对屏幕上的动作，
 * 不用再跟音量键较劲，也不用 adb。
 *
 * 只有「下一句台词」走整条链（§11 那 5 句：onHeard → 假大脑 → dispatch → did 落盘）；
 * 其余按钮直接调 `RobotPort`，**不写事件、不经过 agent 循环** —— 它们验的是 §6 的屏幕姿态，
 * 不是 §4 的主循环。
 *
 * 规格 §6.5 的屏幕是一整块 WebView，没有控件；这个面板是显式偏离，只在 debug 构建里挂
 * （见 `MainActivity`）。每次按下都在 logcat 打一条 `duck-panel`，方便和 espidodes 对照。
 */
class DuckPanel(
    private val activity: Activity,
    private val robot: RobotPort,
    onNextLine: () -> Unit,
    onSnap: () -> Unit = {},
    onView: (Int) -> Unit = {},
    onLog: () -> Unit = {},
    onDance: () -> Unit = {},
    onStop: () -> Unit = {},
) {

    private val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private val toggle = smallButton(EXPANDED).apply {
        setOnClickListener {
            val expand = column.visibility != View.VISIBLE
            column.visibility = if (expand) View.VISIBLE else View.GONE
            text = if (expand) EXPANDED else COLLAPSED
        }
    }

    val view: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(PANEL_BG)
        }
        addView(toggle, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(column)
    }

    /** 8 个机位，绕鸭子 45° 一格，0 = 正前方。按钮上写着当前是第几个。 */
    private var viewIndex = 0

    private val viewButton = smallButton(viewLabel(0)).apply {
        setOnClickListener {
            viewIndex = (viewIndex + 1) % VIEW_COUNT
            text = viewLabel(viewIndex)
            Log.i(TAG, "视角 ${viewIndex + 1}/$VIEW_COUNT")
            onView(viewIndex)
        }
    }

    private fun viewLabel(index: Int): String = "$VIEW_LABEL${index + 1}"

    init {
        add("日志") { onLog() }
        add("站") { robot.stand() }
        add("坐") { robot.sit() }
        add("看左") { robot.gaze(GAZE_DEG, 0f) }
        add("看右") { robot.gaze(-GAZE_DEG, 0f) }
        add("走一步") { step(WALK_VX) }
        add("退一步") { step(-WALK_VX) }
        add("停") {
            onStop()
            robot.stop()
        }
        add("说") { robot.say(WORD) }
        add("拍一张") { onSnap() }
        place(viewButton)
        add("跳舞") { onDance() }
        add("下一句台词") { onNextLine() }
    }

    /**
     * 走固定的一步。**不是「撤销」**：台词和 agent 下发也会让鸭子走，面板记不住那些，
     * 所以要退回来就一次按一步（位置是速度积分出来的，§10：stop 后不再变化）。
     */
    private fun step(vx: Float) {
        robot.velocity(vx, 0f, 0f)
        view.postDelayed({ robot.stop() }, WALK_MS)
    }

    private fun add(label: String, action: () -> Unit) {
        val button = smallButton(label)
        button.setOnClickListener {
            Log.i(TAG, label)
            action()
        }
        place(button)
    }

    /** 右对齐塞进那一列。 */
    private fun place(button: Button) {
        column.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                gravity = Gravity.END
            },
        )
    }

    private fun smallButton(label: String): Button = Button(activity).apply {
        text = label
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(14), dp(2), dp(14), dp(2))
        setTextColor(TEXT)
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(BUTTON_BG)
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        activity.resources.displayMetrics,
    ).toInt()

    private companion object {
        const val TAG = "duck-panel"
        const val PANEL_BG = 0xCC0B0F14.toInt()
        const val BUTTON_BG = 0x33FFFFFF
        const val TEXT = 0xFFE8EEF7.toInt()

        const val EXPANDED = "收起"
        const val COLLAPSED = "动作"

        /** 机位个数和按钮前缀。 */
        const val VIEW_COUNT = 8
        const val VIEW_LABEL = "视角 "

        /** §11 手测里的 yaw 上限：45°。 */
        const val GAZE_DEG = 45f

        /** 和 §11「过来」那条演示一样：0.2 m/s 走 800 ms。 */
        const val WALK_VX = 0.2f
        const val WALK_MS = 800L

        const val WORD = "你好呀"
    }
}
