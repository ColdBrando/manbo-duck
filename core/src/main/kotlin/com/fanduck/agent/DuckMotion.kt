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

// ---------- 动作的"活"从哪来（2026-09-28，emin：像滑行、像慢放）----------

/**
 * 速度的一阶滞后时间常数（秒）。命令说要 0.2 m/s，鸭子不是立刻就在 0.2 —— 它用这个常数
 * 追上去。**起停的重量感全在这个数上**：太小又变回瞬间匀速（滑行），太大就拖泥带水。
 * 0.12 秒大约是一步的 1/4 拍，看着像"蹬地出发 / 收脚刹住"。
 */
const val ACCEL_TAU = 0.12f

/** 步频的两端（Hz）。慢走倒得密一点、步幅小一点，不是"大步慢放"。 */
const val STEP_HZ_MIN = 1.0f
const val STEP_HZ_MAX = 2.6f

/** 待机呼吸的频率：约 3.3 秒一次。 */
const val BREATH_HZ = 0.30f

/** 前后倾的增益：把"还差多少没追上"（最大 0.2 m/s）换成最多 0.06 弧度的脖子倾角。 */
const val LEAN_GAIN = 0.30f

/**
 * 一步一点头的幅度（脖子，2 倍步频）。
 *
 * **为什么这些都加在脖子上**：这个骨架里**躯干是根节点**，整机只有 x/z/yaw 三个自由度
 * （`DuckFrame` 里没有俯仰/横滚），腿和脖子都挂在躯干下面。所以：
 * - 转髋**不会**让身体前倾，只会让两条腿在身子底下前后滑；
 * - 两条膝盖一起屈，只把脚抬高 0.4 毫米，贴地补偿一抵消身体几乎不动（拿管线里的
 *   前向运动学量过，`tools/duckmesh/README.md` 那套）。
 *
 * 能看出"这只鸭子在使劲"的就只有脖子和头 —— 所以起伏、前倾、呼吸都落在下标 5 上。
 */
const val NECK_NOD = 0.05f

/** 待机呼吸的幅度（脖子，弧度）。0.03 大约是头往前走 3 毫米。 */
const val BREATH_NECK = 0.030f

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

    /** 命令要去的速度（`velocity` 写进来）。 */
    private var vx = 0f
    private var vy = 0f
    private var wz = 0f

    /** 真正在用的速度：一阶滞后追上面那三个。起停的"重量"全在这儿。 */
    private var vxSmooth = 0f
    private var vySmooth = 0f
    private var wzSmooth = 0f

    /** 待机呼吸的相位（很慢的正弦，只在没在走的时候看得见）。 */
    private var breathPhase = 0f

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

    /**
     * 一帧。位置（x/z/yaw）用**平滑之后**的速度积分 —— 所以起停有加速/减速，
     * 不是"啪"地从 0 到 0.2（那看着像滑行，2026-09-28 emin 提的）。
     */
    @Synchronized
    fun sample(nowMs: Long): DuckFrame {
        val dt = if (lastMs == Long.MIN_VALUE) 0f
        else ((nowMs - lastMs).coerceIn(0L, 100L)) / 1000f
        lastMs = nowMs

        val k = (dt / ACCEL_TAU).coerceIn(0f, 1f)
        vxSmooth += (vx - vxSmooth) * k
        vySmooth += (vy - vySmooth) * k
        wzSmooth += (wz - wzSmooth) * k

        val linear = planarAmount(vxSmooth, vySmooth)
        val turn = (abs(wzSmooth) / 1f).coerceIn(0f, 1f)
        val amount = maxOf(linear, 0.35f * turn).coerceIn(0f, 1f)
        // 步频跟速度走：慢走是"小步倒得密"，不是"大步慢放"（这条也是 2026-09-28 改的）
        phase += (2f * Math.PI.toFloat() * stepHz(amount)) * dt
        val cy = cos(yaw)
        val sy = sin(yaw)
        x += (vxSmooth * sy + vySmooth * cy) * dt
        z += (vxSmooth * cy - vySmooth * sy) * dt
        yaw += wzSmooth * dt
        amp += ((amount - amp) * (dt / 0.2f).coerceIn(0f, 1f))
        breathPhase += (2f * Math.PI.toFloat() * BREATH_HZ) * dt
        val targetMouth = if (speaking) 0.6f else 0f
        mouth = approach(mouth, targetMouth, 4f * dt)
        val base = blended(nowMs)
        val joints = gait(
            base = base,
            phase = phase,
            amp = amp,
            vy = vySmooth,
            // 还差多少没追上 = 正在加速（或刹车）：身体跟着前后倾一点
            lean = accelLean(vx, vxSmooth),
            // 呼吸只在没在走的时候看得见（amp 大了自然压掉）
            breath = (1f - amp) * sin(breathPhase),
            // 只在"转身但不前进"的时候踏步
            march = (turn - linear).coerceAtLeast(0f),
        )
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
 * 步频（Hz）：跟着"有多快"走。**慢走是倒得密、步幅小**，不是大步慢放 ——
 * 原来固定 1.5 Hz 只改幅度，慢走时就会像慢动作回放。
 */
fun stepHz(amount: Float): Float =
    STEP_HZ_MIN + (STEP_HZ_MAX - STEP_HZ_MIN) * amount.coerceIn(0f, 1f)

/**
 * 起步/刹车的前后倾（弧度）："还差多少没追上"（正 = 在加速，负 = 在刹车）换算成倾角，
 * 限幅在 ±(0.2 × LEAN_GAIN)。起步时身体先往前压一点，停下时往后坐一点。
 */
fun accelLean(vTarget: Float, vSmooth: Float): Float =
    (vTarget - vSmooth).coerceIn(-0.2f, 0.2f) * LEAN_GAIN

/**
 * 先复制 base，再只改腿和脖子。同一时刻给左右髋前后角加上同一个 swing，右腿显示时再取反，
 * 画面上就是一前一后。
 *
 * 2026-09-28（emin：像滑行、像慢放）加的几样，都是为了"不像个在滑的箱子"：
 *
 * - `march`：只在"转身但不前进"时大于 0 —— 把髋的前后摆压小、膝盖抬起来，看着像原地踏步；
 *   不压的话整个身体会像转盘一样滑转。
 * - `lean`：起步往前探、刹车往后坐。**落在脖子上**（见 NECK_NOD 那段：躯干是根节点，
 *   加在髋上只会让腿在身子底下滑）。
 * - `breath`：待机呼吸，很轻。调用方已经乘过 `(1-amp)`，走起来就没了。
 *
 * 还有**一步一点头**（`nod`，2 倍步频）—— 这个骨架没有身体起伏这个自由度，头就是那只
 * "会上下动"的部分，靠它把脚步的节奏带出来。
 */
fun gait(
    base: FloatArray,
    phase: Float,
    amp: Float,
    vy: Float,
    lean: Float = 0f,
    breath: Float = 0f,
    march: Float = 0f,
): FloatArray {
    val out = base.copyOf()
    val left = sin(phase)
    val swing = pitchSwing(phase, amp) * (1f - 0.7f * march)
    out[2] = base[2] + swing
    out[12] = base[12] + swing
    out[3] = base[3] + 0.50f * amp * maxOf(left, 0f) * (1f + 0.8f * march)
    out[13] = base[13] - 0.50f * amp * maxOf(-left, 0f) * (1f + 0.8f * march)
    out[4] = base[4] + 0.15f * swing
    out[14] = base[14] + 0.15f * swing
    // 脖子：一步一点头（2 倍步频）+ 起步前探/刹车后坐 + 呼吸。
    // 方向是拿前向运动学量的：**下标 5 减小 = 头往 +Z（前）走**。
    val nod = amp * (1f - cos(2f * phase)) * 0.5f
    out[5] = base[5] - NECK_NOD * nod - lean + BREATH_NECK * breath
    val roll = 0.20f * (vy / 0.1f) * amp + 0.05f * amp * left
    out[1] = base[1] + roll
    out[11] = base[11] - roll
    return out
}
