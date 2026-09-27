package com.fanduck.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 语音输入。**这里只做驱动，不碰识别本身** —— 识别是 `SttSource`（见 `core/Stt.kt`），
 * 系统自带的两种绑法在 `AndroidSttSource`，换国产/厂商的识别服务就是再写一个 `SttSource`。
 *
 * 它负责的是这些：
 *
 * - **挑源**：`chooseStt` —— 端上优先于云（隐私），点名要的优先，都没有就关掉语音输入。
 * - **换源**：某个源被判死刑（`fatalForSource`：中文不支持 / 语言包没下）时换下一个候选，
 *   而不是在原地重挂。典型场景：端上识别声称可用、但中文包没下 → 退到系统默认。
 * - **两种模式**：前台按住说话（`holdOn`/`holdOff`），后台连续听（`setContinuous`）。
 *   后台没有人能按住屏幕，只有连续听这一种可能；§5 那个"每 6.2 秒一轮 + 录音提示音"
 *   的代价在后台照单全收 —— 实测间隔打 logcat。
 * - **重挂节拍**：`ListenBackoff`（纯的、有断言）。识别器**秒回**时要退避，
 *   不退避就是每秒重挂五次的空转。
 * - **静音窗口**：鸭子说话期间 + 说完 `MUTE_TAIL_MS` 之内，识别结果一律丢弃 ——
 *   不堵的话喇叭的声音会被麦克风听回去，形成自激回环。连续听也照过这道闸。
 */
class VoiceInput(
    context: Context,
    private val mute: MuteWindow,
    private val onHeard: (String) -> Unit,
) {

    private val candidates = AndroidSttSource.candidates(context)
    private val backoff = ListenBackoff()
    private val ui = Handler(Looper.getMainLooper())

    /** 判过死刑的源（中文不支持 / 语言包没下）。拉黑，不然两个都坏时会来回换。 */
    private val dead = mutableSetOf<String>()

    private var source: SttSource? = null
    private var ready = false
    private var listening = false

    /** 连续听（退到后台时）。开着的时候按住说话那套不问事。 */
    private var continuous = false

    /** 连续听重挂了几个来回 —— §5 那个 6.2 秒一轮的实测就靠这个数。 */
    private var cycles = 0
    private var cycleStartedAt = 0L

    /** 现在用的是哪个识别源、音频出不出手机。给日志和界面看。 */
    val statusLine: String
        get() = source?.let { "${it.label}（${if (it.onDevice) "音频不出手机" else "音频会传到它那边"}）" }
            ?: "没有可用的识别服务"

    fun start() {
        if (ready) return
        val chosen = chooseStt(candidates, dead = dead)
        if (chosen == null) {
            Log.i(TAG, "这台设备没有语音识别服务，语音输入没开")
            return
        }
        ready = true
        select(chosen)
    }

    fun stop() {
        ready = false
        listening = false
        continuous = false
        ui.removeCallbacksAndMessages(null)
        source?.destroy()
        source = null
    }

    /**
     * 连续听的开与关。退到后台时开（配合 `DuckService` 的前台服务），回到前台时关。
     *
     * 关的时候要 `stop()` 而不是 `cancel()`：手上那半句让它交完，别把用户刚说的话吞掉。
     */
    fun setContinuous(on: Boolean) {
        if (continuous == on) return
        continuous = on
        if (on) {
            if (!ready) start()
            if (!ready) return
            cycles = 0
            Log.i(TAG, "退到后台：连续听（${statusLine}；§5 的 6.2 秒一轮从这里开始）")
            listenAgain()
        } else {
            Log.i(TAG, "回到前台：停止连续听（共重挂 $cycles 次）")
            ui.removeCallbacksAndMessages(null)
            listening = false
            source?.stop()
        }
    }

    /** 按住屏幕：开始听这一句。连续听开着的时候不接手（后台没人按屏幕，前台会关掉连续听）。 */
    fun holdOn() {
        if (continuous || !ready || listening) return
        val engine = source ?: return
        listening = true
        cycleStartedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "按住，开始听")
        engine.start()
    }

    /** 松开：把这半句交给识别器收尾。 */
    fun holdOff() {
        if (continuous || !listening) return
        Log.i(TAG, "松开，停")
        source?.stop()
    }

    // ---------- 挑中的那个源回调进来 ----------

    private fun onSttEvent(event: SttEvent) {
        listening = false
        when (event) {
            is SttEvent.Heard -> {
                deliver(event.text)
                restartIfContinuous()
            }

            is SttEvent.Failed -> {
                Log.i(TAG, "这一句没听清：${event.message}（码 ${event.code}）")
                val fatal = fatalForSource(event.code)
                if (fatal) source?.let { dead += it.id }
                if (fatal && switchSource()) {
                    if (continuous) listenAgain()
                } else {
                    restartIfContinuous()
                }
            }
        }
    }

    /**
     * 换下一个候选。换成功返回 true（调用方决定要不要立刻开下一轮）。
     * 没得换就把语音输入关掉 —— 别装作还能听。
     */
    private fun switchSource(): Boolean {
        val current = source ?: return false
        val next = nextStt(candidates, current, dead)
        if (next == null) {
            Log.i(TAG, "${current.label}用不了，也没有别的识别源了 —— 语音输入关掉")
            ready = false
            continuous = false
            current.destroy()
            source = null
            return false
        }
        Log.i(TAG, "${current.label}用不了，换 ${next.label}")
        select(next)
        return true
    }

    private fun select(next: SttSource) {
        if (source !== next) source?.destroy()
        source = next
        next.onEvent = ::onSttEvent
        Log.i(TAG, "识别源：${next.id} —— ${statusLine}")
    }

    // ---------- 连续听的节拍 ----------

    /** 连续听一轮：识别器自己会超时结束，结束后由回调再挂上来。 */
    private fun listenAgain() {
        if (!continuous || listening) return
        val engine = source ?: return
        listening = true
        cycles++
        cycleStartedAt = SystemClock.uptimeMillis()
        if (cycles > 1) {
            Log.i(TAG, "连续听第 $cycles 轮，距上一轮 ${cycleStartedAt - lastCycleEndedAt} ms")
        }
        engine.start()
    }

    private var lastCycleEndedAt = 0L

    /**
     * 一轮结束之后接着听。间隔很小是故意的：识别器自己的静默超时才是那个 6.2 秒的节拍，
     * 这里再叠一个长延迟只会让它更迟钝。**秒回**由 `ListenBackoff` 退避。
     */
    private fun restartIfContinuous() {
        if (!continuous) return
        val endedAt = SystemClock.uptimeMillis()
        val delay = backoff.onCycleEnded(cycleStartedAt, endedAt)
        lastCycleEndedAt = endedAt
        if (backoff.backingOff) {
            Log.i(TAG, "上一轮只撑了 ${endedAt - cycleStartedAt} ms（识别器不对劲），退避 $delay ms")
        }
        ui.postDelayed({ listenAgain() }, delay)
    }

    /** 识别结束才走这里（规格 §5）。中间结果不要。 */
    private fun deliver(text: String) {
        when {
            text.isEmpty() -> Unit
            // 时钟要和 MuteWindow 喂进来的那个一致：都是 uptimeMillis
            mute.muted(SystemClock.uptimeMillis()) -> Log.i(TAG, "静音窗口内，丢弃：$text")
            else -> {
                Log.i(TAG, "听到：$text")
                onHeard(text)
            }
        }
    }

    private companion object {
        const val TAG = "duck-voice"
    }
}
