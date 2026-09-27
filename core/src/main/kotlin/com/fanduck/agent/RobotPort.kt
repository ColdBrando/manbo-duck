package com.fanduck.agent

/**
 * 意图的执行结果。
 *
 * 真机的电机服务可以拒绝意图（§12：手柄占用、距离过近、舵机忙…），屏幕实现永远不会拒绝。
 * 形状照 `microduck_rl` 的 `duck-body`：一问一答，失败带原因（评审 P1-4 的返回通道）。
 */
data class Ack(val ok: Boolean, val reason: String = "") {
    companion object {
        val OK = Ack(true)
        fun refused(reason: String) = Ack(false, reason)
    }
}

/**
 * 本机端口（规格 §5）。
 *
 * 手机上 RobotPort 由 ScreenRobot 实现，它没有舵机可写；以后接真机时换一个实现，
 * agent 逻辑一行都不用改。这是整套设计里唯一长期不变的契约。
 *
 * 运动类方法返回 `Ack`：拒绝要能回到 `did` 里（评审 P1-4）。`say` 不返回 ——
 * 它不是电机命令，`dispatch` 也不为它写 `did`（§3.5：说话不受任何过滤影响）。
 */
interface RobotPort {
    fun padActive(): Boolean
    fun stop(): Ack
    fun velocity(vx: Float, vy: Float, wz: Float): Ack
    fun gaze(yaw: Float, pitch: Float): Ack
    fun stand(): Ack
    fun sit(): Ack
    fun say(text: String)
}

/** 云端推理。单测用假实现。 */
interface CloudClient {
    fun complete(messages: List<Map<String, String>>): String
}

/** 感知。手机上由相机+距离传感器实现；没有检测器时只返回一句固定说明。 */
interface SensePort {
    fun captionAndDistance(): Pair<String, Float?>
}
