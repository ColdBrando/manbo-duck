package com.fanduck.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 系统自带的识别器，两种绑法：
 *
 * - `onDevice = true`：`createOnDeviceSpeechRecognizer`（API 31+）。识别在这台设备上做，
 *   **音频不出手机**。模型还是 Google 的（Android System Intelligence），中文够不够好用看设备，
 *   语言包没下会报 13（`ERROR_LANGUAGE_UNAVAILABLE`）—— 那种情况由上层换下一个源。
 * - `onDevice = false`：`createSpeechRecognizer`，绑设备上的**默认**识别器。谁提供由设备决定：
 *   GMS 机器上通常是 Google 的（识别在它服务器上做），国行 ROM 上一般是厂商的（也是传到厂商
 *   服务器）。所以这一路 `onDevice` 是 **false** —— 别为了好听写成 true。
 *
 * 两个都要主线程调用（`SpeechRecognizer` 的硬要求）。`VoiceInput` 本来就在主线程上。
 *
 * ---
 * **接国产/厂商识别服务的模板**（讯飞、小米、华为…）：新建一个类实现 `core` 的 `SttSource`，
 * 把 SDK 的回调翻成 `SttEvent`，就这么多：
 *
 * ```kotlin
 * class IflytekSttSource(context: Context) : SttSource {
 *     override val id = "iflytek"
 *     override val label = "讯飞"
 *     override val onDevice = false          // 讯飞的在线识别也是走它的服务器
 *     override val available = runCatching { SpeechUtility.getUtility() != null }
 *         .getOrDefault(false)
 *     override var onEvent: ((SttEvent) -> Unit)? = null
 *
 *     override fun start() {
 *         // 装机、初始化、startListening —— 把 onResult 翻成：
 *         onEvent?.invoke(SttEvent.Heard(text))
 *         // 把 onError 翻成：
 *         onEvent?.invoke(SttEvent.Failed(code, sttErrorText(code)))
 *     }
 *     override fun stop() = Unit      // 收尾（有就调）
 *     override fun cancel() = Unit    // 取消
 *     override fun destroy() = Unit   // 释放
 * }
 * ```
 *
 * 然后在 `SttSources.available(context)` 里把它加进候选，其余（排队、静音窗口、连续听的
 * 退避、前台服务的生命周期）一个字都不用改。
 */
class AndroidSttSource(
    private val context: Context,
    override val onDevice: Boolean,
) : SttSource, RecognitionListener {

    override val id: String = if (onDevice) ID_ON_DEVICE else ID_SYSTEM

    override val label: String = if (onDevice) "端上识别" else "系统识别"

    override val available: Boolean
        get() = if (onDevice) {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            SpeechRecognizer.isRecognitionAvailable(context)
        }

    override var onEvent: ((SttEvent) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null

    override fun start() {
        val engine = engine() ?: run {
            onEvent?.invoke(SttEvent.Failed(CODE_NO_ENGINE, sttErrorText(CODE_NO_ENGINE)))
            return
        }
        runCatching { engine.startListening(request()) }
            .onFailure { onEvent?.invoke(SttEvent.Failed(CODE_NO_ENGINE, it.message.orEmpty())) }
    }

    override fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() {
        runCatching { recognizer?.cancel() }
    }

    override fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun engine(): SpeechRecognizer? {
        recognizer?.let { return it }
        val created = runCatching {
            if (onDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.onFailure { Log.i(TAG, "识别器建不起来（$id）：${it.message}") }.getOrNull()
        return created?.also {
            it.setRecognitionListener(this)
            recognizer = it
        }
    }

    /** 每个源自己的参数都在这里 —— 换 SDK 就换这个。 */
    private fun request(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // 不设 EXTRA_PREFER_OFFLINE：系统那一路由设备自己决定在哪儿识别，
        // 我们只用 onDevice 这个字段如实告诉上层"这一路音频出不出手机"。
    }

    // ---------- RecognitionListener ----------

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onError(error: Int) {
        onEvent?.invoke(SttEvent.Failed(error, sttErrorText(error)))
    }

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        // 空结果也算"这一轮没听清"：这样上层只需要处理 Heard / Failed 两种结局，
        // 每一轮都必然有一个结局。（空文本喂给 onHeard 也没意义。）
        if (text.isEmpty()) {
            onEvent?.invoke(SttEvent.Failed(CODE_EMPTY, sttErrorText(CODE_EMPTY)))
        } else {
            onEvent?.invoke(SttEvent.Heard(text))
        }
    }

    companion object {
        const val ID_ON_DEVICE = "on-device"
        const val ID_SYSTEM = "system"

        private const val TAG = "duck-voice"
        private const val LANGUAGE = "zh-CN"

        /** 识别器都没建起来（没有识别服务、权限被撤、SDK 没初始化）。 */
        const val CODE_NO_ENGINE = -1

        /** 识别结束但一个字都没有（系统那边叫 ERROR_NO_MATCH = 7）。 */
        const val CODE_EMPTY = 7

        /**
         * 这台设备上按"音频出不出手机"排好序的候选。
         * 端上排前面，系统默认垫后 —— 挑哪个由 `chooseStt` 决定（那边有断言）。
         */
        fun candidates(context: Context): List<SttSource> = listOf(
            AndroidSttSource(context, onDevice = true),
            AndroidSttSource(context, onDevice = false),
        )
    }
}
