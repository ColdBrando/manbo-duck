package com.fanduck.agent

/**
 * 本机端口（规格 §5）。
 *
 * 手机上 RobotPort 由 ScreenRobot 实现，它没有舵机可写；以后接真机时换一个实现，
 * agent 逻辑一行都不用改。这是整套设计里唯一长期不变的契约。
 *
 * 已知缺口（评审 P1-4）：真机的电机服务可以拒绝意图（§12），但这个接口全是 Unit，
 * 拒绝回不来，did 只能记 ok。接真机之前要给它加返回结果。
 */
interface RobotPort {
    fun padActive(): Boolean
    fun stop()
    fun velocity(vx: Float, vy: Float, wz: Float)
    fun gaze(yaw: Float, pitch: Float)
    fun stand()
    fun sit()
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
