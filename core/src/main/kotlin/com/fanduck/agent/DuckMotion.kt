package com.fanduck.agent

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 规格 §6.2 的关节表。下标 0..14，单位弧度。站立角用镜像工作台的默认预览角，
 * 这是屏幕上的「站」，不是去写舵机的标定值。禁止改这两个数组里的元素。
 */
val STAND = floatArrayOf(
    0f, -0.0873f, -0.4579f, -0.0049f, 0.4530f,
    0.3491f, 0.3491f, 0f, 0f, 0f,
    0f, 0.0873f, 0.4579f, 0.0049f, -0.4530f,
)

/** 坐下角只给屏幕用。真机坐下仍是 sit 意图，不使用这组数。 */
val SIT = floatArrayOf(
    0f, -0.0873f, -1.20f, 1.40f, -0.15f,
    0.3491f, 0.3491f, 0f, 0f, 0f,
    0f, 0.0873f, 1.20f, -1.40f, 0.15f,
)

data class DuckFrame(
    val joints: FloatArray,   // 长度 15，弧度
    val x: Float,
    val z: Float,
    val yaw: Float,
)

/**
 * 规格 §6.3。地面：Y 朝上，鸭子面朝 +Z。yaw 增大时俯视逆时针，鸭头从 +Z 转向 +X。
 * vx > 0 朝鸭头走，vy > 0 向鸭的左侧平移。
 *
 * 规格 §6.4 要求"两边只共享一个 DuckMotion，用同一把锁包住 stop/velocity/gaze/stand/sit/
 * setSpeaking/sample"。这里直接把方法标成 @Synchronized（可重入），比要求调用方自己记得加锁更稳。
 */
class DuckMotion {

    private var lastMs = Long.MIN_VALUE
    private var phase = 0f
    private var amp = 0f
    private var mouth = 0f
    private var speaking = false
    private var vx = 0f
    private var vy = 0f
    private var wz = 0f
    private var x = 0f
    private var z = 0f
    private var yaw = 0f
    private var gazeYawDeg = 0f
    private var gazePitchDeg = 0f
    private var fromPose = STAND.copyOf()
    private var toPose = STAND.copyOf()
    private var blendStart = 0L
    private var blendMs = 1L

    @Synchronized
    fun stop() {
        vx = 0f; vy = 0f; wz = 0f
    }

    @Synchronized
    fun velocity(vx: Float, vy: Float, wz: Float) {
        this.vx = vx; this.vy = vy; this.wz = wz
        if (!toPose.contentEquals(STAND)) beginBlend(STAND, 200)
    }

    @Synchronized
    fun gaze(yawDeg: Float, pitchDeg: Float) {
        gazeYawDeg = yawDeg
        gazePitchDeg = pitchDeg
    }

    @Synchronized
    fun stand() = beginBlend(STAND, 2000)

    @Synchronized
    fun sit() = beginBlend(SIT, 2000)

    @Synchronized
    fun setSpeaking(on: Boolean) {
        speaking = on
    }

    @Synchronized
    fun sample(nowMs: Long): DuckFrame {
        val dt = if (lastMs == Long.MIN_VALUE) 0f
        else ((nowMs - lastMs).coerceIn(0L, 100L)) / 1000f
        lastMs = nowMs
        val amount = maxOf(planarAmount(vx, vy), 0.35f * (abs(wz) / 1f)).coerceIn(0f, 1f)
        phase += (2f * Math.PI.toFloat() * 1.5f * amount) * dt
        val cy = cos(yaw)
        val sy = sin(yaw)
        x += (vx * sy + vy * cy) * dt
        z += (vx * cy - vy * sy) * dt
        yaw += wz * dt
        amp += ((amount - amp) * (dt / 0.2f).coerceIn(0f, 1f))
        val targetMouth = if (speaking) 0.6f else 0f
        mouth = approach(mouth, targetMouth, 4f * dt)
        val base = blended(nowMs)
        val joints = gait(base, phase, amp, vy)
        // 评审 P2：gaze 的 ±45° 是相对角，叠加 base[6]（站立 20°）后超过 §6 表里声明的 ±45° 物理范围。
        // 按规格原文实现，要不要改成 ±(45° - base[6]) 由你定。
        joints[6] = base[6] + gazePitchDeg * Math.PI.toFloat() / 180f
        joints[7] = gazeYawDeg * Math.PI.toFloat() / 180f
        joints[8] = base[8]
        joints[9] = mouth
        return DuckFrame(joints, x, z, yaw)
    }

    private fun beginBlend(target: FloatArray, ms: Int) {
        val now = if (lastMs == Long.MIN_VALUE) 0L else lastMs
        fromPose = blended(now)
        toPose = target
        blendStart = now
        blendMs = ms.toLong().coerceAtLeast(1L)
    }

    private fun blended(nowMs: Long): FloatArray {
        val t = (nowMs - blendStart).toFloat() / blendMs.toFloat()
        return blendedAt(fromPose, toPose, t)
    }
}

fun approach(current: Float, target: Float, maxStep: Float): Float {
    val delta = target - current
    if (abs(delta) <= maxStep) return target
    return current + sign(delta) * maxStep
}

fun planarAmount(vx: Float, vy: Float): Float {
    val speed = sqrt(vx * vx + vy * vy)
    return (speed / 0.2f).coerceIn(0f, 1f)
}

fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

fun blendedAt(from: FloatArray, to: FloatArray, t: Float): FloatArray {
    val s = smoothstep(t)
    return FloatArray(15) { i -> from[i] + (to[i] - from[i]) * s }
}

fun pitchSwing(phase: Float, amp: Float): Float = 0.40f * amp * sin(phase)

/**
 * 先复制 base，再只改腿。同一时刻给左右髋前后角加上同一个 swing，右腿显示时再取反，
 * 画面上就是一前一后。
 */
fun gait(base: FloatArray, phase: Float, amp: Float, vy: Float): FloatArray {
    val out = base.copyOf()
    val swing = pitchSwing(phase, amp)
    val left = sin(phase)
    out[2] = base[2] + swing
    out[12] = base[12] + swing
    out[3] = base[3] + 0.50f * amp * maxOf(left, 0f)
    out[13] = base[13] - 0.50f * amp * maxOf(-left, 0f)
    out[4] = base[4] + 0.15f * swing
    out[14] = base[14] + 0.15f * swing
    val roll = 0.20f * (vy / 0.1f) * amp
    out[1] = base[1] + roll
    out[11] = base[11] - roll
    return out
}
