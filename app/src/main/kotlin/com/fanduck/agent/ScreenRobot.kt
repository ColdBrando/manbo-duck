package com.fanduck.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 规格 §5 / §6.4：手机上的 RobotPort。它没有舵机可写，运动意图一律交给 DuckMotion，
 * 说话用 TextToSpeech 同时张开屏幕上的嘴。不要因为没有电机就把 velocity 记成 fail。
 */
class ScreenRobot(
    private val motion: DuckMotion,
    context: Context,
    /** 说话状态变化时通知外面：静音窗口靠它盖住麦克风（见 `MuteWindow`）。 */
    private val onSpeaking: (Boolean) -> Unit = {},
) : RobotPort, TextToSpeech.OnInitListener {

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            return
        }
        val engine = tts ?: return
        val result = engine.setLanguage(Locale.CHINA)
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                speaking(false)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                speaking(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                speaking(false)
            }
        })
    }

    override fun padActive(): Boolean = false   // 手机没有手柄

    override fun stop() = motion.stop()

    override fun velocity(vx: Float, vy: Float, wz: Float) = motion.velocity(vx, vy, wz)

    override fun gaze(yaw: Float, pitch: Float) = motion.gaze(yaw, pitch)

    override fun stand() = motion.stand()

    override fun sit() = motion.sit()

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

    /** 张嘴和静音窗口必须一起动：一个管画面，一个管麦克风。 */
    private fun speaking(on: Boolean) {
        motion.setSpeaking(on)
        onSpeaking(on)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object {
        const val UTTERANCE_ID = "duck-say"

        /** logcat 里鸭子说过的话都在这个 tag 下。 */
        const val TAG = "duck-say"
    }
}
