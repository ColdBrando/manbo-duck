package com.fanduck.agent

/**
 * 静音窗口。**规格 §5 里原本没有这一条**，是接语音识别时必须补的：
 * 鸭子说话时喇叭的声音会被麦克风听回去、识别成一句话再送去云端 —— 自激回环。
 *
 * 规则：TTS 说话期间一律静音；说完之后再静音 `tailMs`（喇叭余音 + 识别往返的延迟）。
 * 时间由调用方给（`SystemClock.uptimeMillis()`），所以这段逻辑是纯的，能在 JVM 上测。
 *
 * 放在 :core 而不是 :app，是因为它是"听到一句"这条链的入口规则，和 §4.2 一样该被断言盖住。
 * §10 那张表是规格里写死的断言，这条是新加的，测试另开一个文件（`MuteWindowTest`）。
 */
const val MUTE_TAIL_MS = 800L

class MuteWindow(private val tailMs: Long = MUTE_TAIL_MS) {

    private var talking = false
    private var tailUntil = Long.MIN_VALUE

    /** 由 `ScreenRobot` 在开口（on=true）和说完（on=false）时调用。 */
    @Synchronized
    fun noteSpeaking(nowMs: Long, on: Boolean) {
        talking = on
        if (!on) tailUntil = nowMs + tailMs
    }

    @Synchronized
    fun muted(nowMs: Long): Boolean = talking || nowMs < tailUntil
}
