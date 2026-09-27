package com.fanduck.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 规格 §5：语音用本机识别，**识别结束才调用 `onHeard`**；音频不落盘、不上传（只拿文字，
 * 不碰 `EXTRA_AUDIO`，也不写 `voice/`）。
 *
 * **按住说话**（push-to-talk）：按住屏幕 `holdOn()` 开一次识别，松开 `holdOff()` 让识别器
 * 把这半句交出来（它会回调 `onResults`）。闲置时识别器不工作。
 *
 * 为什么不做成"一直在听"：不说话时识别器几秒就超时结束一次，要接着听得立刻重挂，
 * 于是变成每 6 秒一轮 —— 系统的录音提示音一直响、麦克风一直占着（实测 logcat 里
 * `识别出错 2，重挂` 每 6.2 秒一条，停不下来）。见文档 §13。
 *
 * 静音窗口（规格里原本没有，接语音时必须补，见 `MuteWindow`）：鸭子说话期间和说完后
 * `MUTE_TAIL_MS` 之内，识别结果一律丢弃 —— 不然喇叭的声音会被麦克风听回去，形成自激回环。
 */
class VoiceInput(
    private val context: Context,
    private val mute: MuteWindow,
    private val onHeard: (String) -> Unit,
) {

    private var recognizer: SpeechRecognizer? = null
    private var ready = false
    private var listening = false

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
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    /** 按住屏幕：开始听这一句。 */
    fun holdOn() {
        if (!ready || listening) return
        val engine = recognizer ?: return
        listening = true
        Log.i(TAG, "按住，开始听")
        runCatching { engine.startListening(request()) }
            .onFailure { Log.i(TAG, "startListening 失败：${it.message}") }
    }

    /** 松开：把这半句交给识别器收尾。 */
    fun holdOff() {
        if (!listening) return
        listening = false
        Log.i(TAG, "松开，停")
        runCatching { recognizer?.stopListening() }
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
            // 按住说话不需要重挂：没听清就没听清，松手再按一次即可。
            listening = false
            Log.i(TAG, "这一句没听清（错误码 $error）")
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            deliver(text)
        }
    }

    private companion object {
        const val TAG = "duck-voice"
        const val LANGUAGE = "zh-CN"
    }
}
