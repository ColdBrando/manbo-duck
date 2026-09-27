package com.fanduck.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 规格 §5：语音用本机识别，**识别结束才调用 `onHeard`**；音频不落盘、不上传（只拿文字，
 * 不碰 `EXTRA_AUDIO`，也不写 `voice/`）。
 *
 * 两种模式，按鸭子可不可见切：
 *
 * - **看得见（前台）：按住说话**。按住屏幕 `holdOn()` 开一次识别，松开 `holdOff()` 让识别器
 *   把这半句交出来（它会回调 `onResults`）。闲置时识别器不工作 —— §5 明确不要"一直在听"：
 *   不说话时识别器几秒就超时结束一次，要接着听得立刻重挂，于是变成每 6 秒一轮，系统的录音
 *   提示音一直响、麦克风一直占着（实测 logcat 里每 6.2 秒一条，停不下来）。见文档 §13。
 * - **看不见（后台）：连续听**（`setContinuous(true)`）。后台没有人能按住屏幕，所以只有这一种
 *   可能 —— 上面那个 6.2 秒一轮的代价在这里**照单全收**，只是它现在只发生在用户主动把鸭子
 *   留在后台的时候。`onPause` 起前台服务让它合法（`DuckService`），点通知回来就停。
 *
 * 静音窗口（规格里原本没有，接语音时必须补，见 `MuteWindow`）：鸭子说话期间和说完后
 * `MUTE_TAIL_MS` 之内，识别结果一律丢弃 —— 不然喇叭的声音会被麦克风听回去，形成自激回环。
 * **连续听也照过这道闸**：鸭子自己在说话时，它听到的是自己。
 */
class VoiceInput(
    private val context: Context,
    private val mute: MuteWindow,
    private val onHeard: (String) -> Unit,
) {

    private var recognizer: SpeechRecognizer? = null
    private var ready = false
    private var listening = false

    /** 连续听（后退到后台时）。开着的时候按住说话那套不问事。 */
    private var continuous = false

    /** 连续听重挂了几个来回 —— §5 那个 6.2 秒一轮的实测就靠这个数。 */
    private var cycles = 0
    private var lastCycleAt = 0L

    /** 重挂的退避时长（识别器连续秒回时翻倍，见 `restartIfContinuous`）。 */
    private var backoffMs = RESTART_DELAY_MS

    private val ui = Handler(Looper.getMainLooper())

    fun start() {
        if (ready) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.i(TAG, "这台设备没有语音识别服务，语音输入没开")
            return
        }
        ready = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    fun stop() {
        ready = false
        listening = false
        continuous = false
        ui.removeCallbacksAndMessages(null)
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    /**
     * 连续听的开与关。退到后台时开（配合 `DuckService` 的前台服务），回到前台时关。
     *
     * 关的时候要 `stopListening` 而不是 `cancel`：手上那半句让它交完，别把用户刚说的话吞掉。
     */
    fun setContinuous(on: Boolean) {
        if (continuous == on) return
        continuous = on
        if (on) {
            if (!ready) start()
            if (!ready) return
            cycles = 0
            Log.i(TAG, "退到后台：连续听（§5 的 6.2 秒一轮从这里开始）")
            listenAgain()
        } else {
            Log.i(TAG, "回到前台：停止连续听（共重挂 $cycles 次）")
            ui.removeCallbacksAndMessages(null)
            listening = false
            runCatching { recognizer?.stopListening() }
        }
    }

    /** 按住屏幕：开始听这一句。连续听开着的时候不接手（后台没人按屏幕，前台会关掉连续听）。 */
    fun holdOn() {
        if (continuous || !ready || listening) return
        val engine = recognizer ?: return
        listening = true
        Log.i(TAG, "按住，开始听")
        runCatching { engine.startListening(request()) }
            .onFailure { Log.i(TAG, "startListening 失败：${it.message}") }
    }

    /** 松开：把这半句交给识别器收尾。 */
    fun holdOff() {
        if (continuous || !listening) return
        listening = false
        Log.i(TAG, "松开，停")
        runCatching { recognizer?.stopListening() }
    }

    /** 连续听一轮：识别器自己会超时结束，结束后由回调再挂上来。 */
    private fun listenAgain() {
        if (!continuous || listening) return
        val engine = recognizer ?: return
        listening = true
        cycles++
        // 每轮把间隔打出来：§5 说的"实测每 6.2 秒一轮"就是拿这个数量的，别靠感觉
        val now = SystemClock.uptimeMillis()
        if (cycles > 1) Log.i(TAG, "连续听第 $cycles 轮，距上一轮 ${now - lastCycleAt} ms")
        lastCycleAt = now
        runCatching { engine.startListening(request()) }
            .onFailure {
                listening = false
                Log.i(TAG, "连续听没挂上：${it.message}，${RESTART_DELAY_MS} ms 后再试")
                ui.postDelayed({ listenAgain() }, RESTART_DELAY_MS)
            }
    }

    /**
     * 一轮结束（听清了或者超时/出错）之后接着听。间隔很小是故意的：识别器自己的静默超时
     * 才是那个 6.2 秒的节拍，这里再叠一个长延迟只会让它更迟钝。
     *
     * 但**秒回**要退避：识别服务不可用时（模拟器上就是错误码 2）`startListening` 立刻报错，
     * 不退避就成了每秒重挂五次的空转。所以一轮短于 `MIN_CYCLE_MS` 就翻倍等，恢复正常再收回来。
     */
    private fun restartIfContinuous() {
        if (!continuous) return
        val elapsed = SystemClock.uptimeMillis() - lastCycleAt
        backoffMs = if (elapsed < MIN_CYCLE_MS) {
            minOf(backoffMs * 2, MAX_BACKOFF_MS)
        } else {
            RESTART_DELAY_MS
        }
        if (backoffMs > RESTART_DELAY_MS) {
            Log.i(TAG, "上一轮只撑了 $elapsed ms（识别器不对劲），退避 ${backoffMs} ms")
        }
        ui.postDelayed({ listenAgain() }, backoffMs)
    }

    private fun request(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
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

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onError(error: Int) {
            // 按住说话不需要重挂：没听清就没听清，松手再按一次即可。连续听才重挂。
            listening = false
            Log.i(TAG, "这一句没听清（错误码 $error）")
            restartIfContinuous()
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            deliver(text)
            restartIfContinuous()
        }
    }

    private companion object {
        const val TAG = "duck-voice"
        const val LANGUAGE = "zh-CN"

        /** 一轮结束后隔多久挂下一轮。节拍由识别器自己的静默超时决定（实测 6.2 秒）。 */
        const val RESTART_DELAY_MS = 200L

        /** 一轮短于这个数就算"没真的听起来"，重挂要退避（见 restartIfContinuous）。 */
        const val MIN_CYCLE_MS = 1_000L
        const val MAX_BACKOFF_MS = 5_000L
    }
}
