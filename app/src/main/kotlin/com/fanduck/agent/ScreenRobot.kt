package com.fanduck.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * 规格 §5 / §6.4：手机上的 RobotPort。它没有舵机可写，运动意图一律交给 DuckMotion，
 * 说话用 TextToSpeech 同时张开屏幕上的嘴。不要因为没有电机就把 velocity 记成 fail。
 *
 * **说话的音质**（2026-09-28 收拾过一轮）—— 影响音质的其实是这三件事，都不在"说什么"上：
 *
 * 1. **谁在说**：系统默认引擎如果是 Pico 那种老引擎，怎么调都难听。这里按
 *    "系统默认 → 装着的其它引擎（跳过已知很差的）"依次尝试，直到有一个能说中文的。
 * 2. **哪个嗓子**：同一个引擎对中文往往有好几个 voice，质量从 VERY_LOW 到 VERY_HIGH 差得
 *    很远，默认给的那个常常是"compact"（低质量、体积小）那个。这里挑质量最高的；
 *    同质量优先挑不用联网的（联网 voice 在国内可能根本拉不下来）。
 * 3. **走哪条音量/焦点**：默认的 audio attributes 是"无障碍"，不少机器上音量偏小、
 *    还会和自己的跳舞音乐打架。这里声明成"助手说话"，说话期间申请一个 MAY_DUCK 的
 *    音频焦点 —— 音乐让一让，鸭子的声音压得住。
 *
 * 选中的引擎/嗓子/质量都打进 logcat（`duck-say`），日志页的「事件」里也能看到它说过的话。
 */
class ScreenRobot(
    private val motion: DuckMotion,
    context: Context,
    /** 说话状态变化时通知外面：静音窗口靠它盖住麦克风（见 `MuteWindow`）。 */
    private val onSpeaking: (Boolean) -> Unit = {},
) : RobotPort, TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false

    /**
     * 已经试到链上的第几个（链的第 0 项是"系统默认"）。
     * 构造 `TextToSpeech` 时不给引擎名 = 用系统默认，所以开机就等于已经试过第 0 项。
     */
    private var engineAttempt = 0
    private var engineChain: List<String?> = listOf(null)

    /** 现在用的引擎/嗓子，给人看。音质差的时候先看这一行。 */
    var voiceLine: String = "还没初始化"
        private set

    init {
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.i(TAG, "引擎 ${engineChain.getOrNull(engineAttempt) ?: "系统默认"} 起不来（$status）")
            tryNextEngine()
            return
        }
        val engine = tts ?: return
        val locale = pickLocale(engine)
        if (locale == null) {
            Log.i(TAG, "${engineNameOf(engine)} 不会说中文")
            tryNextEngine()
            return
        }
        val voice = pickVoice(engine, locale)
        if (voice != null) {
            // 先设嗓子再设语言：反过来的话 setLanguage 会把 voice 复位
            engine.voice = voice
        } else {
            engine.language = locale
        }
        engine.setAudioAttributes(AUDIO_ATTRIBUTES)
        engine.setSpeechRate(SPEECH_RATE)
        engine.setPitch(PITCH)
        ready = true
        voiceLine = buildString {
            append(engineNameOf(engine))
            append(" / ")
            append(if (voice != null) "${voice.name}（${qualityText(voice)}）" else "${locale.language} 默认嗓子")
            if (voice?.isNetworkConnectionRequired == true) append(" · 要联网")
            append(" · ${"%.2f".format(PITCH)} 音高")
        }
        Log.i(TAG, "音色：$voiceLine")
        engine.setOnUtteranceProgressListener(progress)
    }

    // ---------- 引擎链：系统默认 → 其它装着的中文引擎 ----------

    private fun tryNextEngine() {
        // 第一次失败时才去枚举装着的引擎（省得白建一遍）
        if (engineChain.size == 1) engineChain = listOf(null) + installedEngines()
        engineAttempt++
        if (engineAttempt >= engineChain.size) {
            ready = false
            voiceLine = "试过 ${engineChain.size} 个引擎，都不会说中文"
            Log.i(TAG, voiceLine)
            return
        }
        val next = engineChain[engineAttempt]
        Log.i(TAG, "换引擎试试：${next ?: "系统默认"}")
        runCatching { tts?.shutdown() }
        tts = TextToSpeech(appContext, this, next)
    }

    /**
     * 装着的引擎，跳过已知很差的（Pico 是 AOSP 那个机器人嗓），去掉系统默认那个（已经试过）。
     * `getEngines()` 是**实例**方法（`android.jar` 里就是这么声明的），所以得问当前这个实例。
     */
    private fun installedEngines(): List<String> {
        val engine = tts ?: return emptyList()
        val default = runCatching { engine.defaultEngine }.getOrNull()
        return runCatching { engine.engines }
            .getOrDefault(emptyList())
            .map { it.name }
            .filter { it != default && it !in KNOWN_BAD_ENGINES }
    }

    // ---------- 语言和嗓子 ----------

    private fun pickLocale(engine: TextToSpeech): Locale? = LOCALES.firstOrNull { locale ->
        val result = runCatching { engine.isLanguageAvailable(locale) }.getOrDefault(-2)
        result == TextToSpeech.LANG_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    /**
     * 挑嗓子：质量最高的优先；同质量优先**不用联网**的（联网 voice 在国内经常拉不下来）；
     * 再同就看名字，保证每次挑到同一个（不然音色会飘）。
     */
    private fun pickVoice(engine: TextToSpeech, locale: Locale): Voice? {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == locale.language }
        if (voices.isEmpty()) return null
        return voices.sortedWith(
            compareByDescending<Voice> { it.quality }
                .thenBy { it.isNetworkConnectionRequired }
                .thenBy { it.name },
        ).first()
    }

    private fun qualityText(voice: Voice): String = when (voice.quality) {
        Voice.QUALITY_VERY_HIGH -> "最高"
        Voice.QUALITY_HIGH -> "高"
        Voice.QUALITY_NORMAL -> "一般"
        Voice.QUALITY_LOW -> "低"
        Voice.QUALITY_VERY_LOW -> "很低"
        else -> "未知"
    }

    private fun engineNameOf(engine: TextToSpeech): String =
        runCatching { engine.defaultEngine }.getOrNull() ?: "默认引擎"

    // ---------- 说话 ----------

    override fun padActive(): Boolean = false   // 手机没有手柄

    // 屏幕上没有会拒绝意图的电机：这几个一律 ok。真机实现的 Ack 才有可能是 false（§12）。
    override fun stop(): Ack {
        motion.stop()
        return Ack.OK
    }

    override fun velocity(vx: Float, vy: Float, wz: Float): Ack {
        motion.velocity(vx, vy, wz)
        return Ack.OK
    }

    override fun gaze(yaw: Float, pitch: Float): Ack {
        motion.gaze(yaw, pitch)
        return Ack.OK
    }

    override fun stand(): Ack {
        motion.stand()
        return Ack.OK
    }

    override fun sit(): Ack {
        motion.sit()
        return Ack.OK
    }

    override fun say(text: String) {
        if (text.isBlank()) return
        // 说出来之前先留一份在 logcat：模拟器/真机上音量小的时候，"它到底说了什么"看这里
        Log.i(TAG, text)
        speaking(true)
        val engine = tts
        if (ready && engine != null) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } else {
            // 初始化失败时不要崩：按每个字 80 ms、至少 400 ms 保持张嘴，然后闭嘴。
            val ms = maxOf(400L, 80L * text.length)
            handler.postDelayed({ speaking(false) }, ms)
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = speaking(false)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = speaking(false)

        override fun onError(utteranceId: String?, errorCode: Int) = speaking(false)
    }

    /** 张嘴和静音窗口必须一起动：一个管画面，一个管麦克风。说话的这段时间把音频焦点也拿着。 */
    private fun speaking(on: Boolean) {
        motion.setSpeaking(on)
        if (on) requestFocus() else abandonFocus()
        onSpeaking(on)
    }

    /**
     * 说话期间拿一个 MAY_DUCK 的焦点：跳舞的音乐让一让，鸭子的声音压得住。
     * 瞬时的（TRANSIENT），说完就还 —— 不抢别人的。
     */
    private fun requestFocus() {
        runCatching { audio.requestAudioFocus(focus) }
            .onFailure { Log.i(TAG, "音频焦点没拿到：${it.message}") }
    }

    private fun abandonFocus() {
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }

    private val focus: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .setOnAudioFocusChangeListener { }
            .build()

    fun shutdown() {
        abandonFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object {
        const val UTTERANCE_ID = "duck-say"

        /** logcat 里鸭子说过的话都在这个 tag 下。 */
        const val TAG = "duck-say"

        /** 先说中文（大陆），再退到"只要是中文"。 */
        val LOCALES = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINA, Locale.CHINESE)

        /**
         * AOSP 自带的 Pico：能说中文但像机器人，比它还差的没几个。
         * 只在"系统默认不会中文"、要挨个试的时候才跳过它。
         */
        val KNOWN_BAD_ENGINES = setOf("com.svox.pico")

        /**
         * 声明成"助手在说话"：比默认的无障碍用途音量正常，也不会跟媒体流抢得莫名其妙。
         * CONTENT_TYPE_SPEECH 让系统的后处理（比如某些机器的助听补偿）按人声来。
         */
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        /** 比默认略慢一点、略高一点：短句更清楚，也不至于尖。 */
        const val SPEECH_RATE = 1.0f
        const val PITCH = 1.05f
    }
}
