package com.fanduck.agent

import org.json.JSONObject

/**
 * 真机训出来的步态。
 *
 * 素材是 `tools/duckgait/bake_gait.py` 从真机策略的录屏里烘出来的（`assets/duck/gaits.json`）：
 * `pollen-robotics/microduck-policies` v5 的 `velstand.onnx` 在 MuJoCo + 真机 BAM 电机模型里
 * 跑 50 Hz，把一个周期（20 帧 = 400 ms = 2.5 Hz）切下来。
 *
 * 屏幕上的鸭子播这个，走的就是**真机的那套步态**，不是手调的 sine —— 连头跟着左右摆
 * （head_yaw 峰峰 15°）都是策略自己算出来的配平动作。
 *
 * **和真机不一样的一处（有意的）**：真机的 `velstand` 有死区 —— 低于约 0.3 m/s 它选择站着
 * （实测 0.25 站、0.30 走），它得靠这个保住平衡。屏幕鸭子没有平衡问题，而且**站着不动却
 * 照常位移就是滑行**（那正是 2026-09-28 要修的东西）。所以这里只要在走就用真步态，
 * 位置照指令积分，低速时会有一点脚底打滑 —— 游戏里的通行做法，比滑行好看得多。
 */

/** 快过这个速度就算"在走"（用真步态）。比这慢就是站着。 */
const val WALK_CLIP_MIN = 0.02f

/** 一个动作片段：一个周期，循环播。 */
class GaitClip(
    val name: String,
    val vx: Float,
    val vy: Float,
    val wz: Float,
    val hz: Float,
    /** frames × 14：关节角**相对 STAND 的偏移**（弧度），不含嘴（下标 9）。 */
    val offsets: List<FloatArray>,
) {
    val frames: Int get() = offsets.size

    /** 一个周期多少毫秒（真机上 20 帧 @50 Hz = 400 ms）。 */
    val periodMs: Float get() = if (hz <= 0f) 1f else frames / hz * 1000f

    /** 这段是按哪个方向录的。给挑片段用。 */
    fun matches(vx: Float, vy: Float, wz: Float): Boolean {
        // 只有一段的时候就是"前进"，以后加了转身/横移再按主方向分
        if (this.vx != 0f && vx * this.vx <= 0f) return false
        if (this.vy != 0f && vy * this.vy <= 0f) return false
        if (this.wz != 0f && wz * this.wz <= 0f) return false
        return true
    }

    /**
     * 按相位（0..1，可以超出去）取一帧，摊进 15 个关节角：`base` 是基准姿态（STAND 或坐姿），
     * 偏移加到 14 个下标上，**嘴（下标 9）不动** —— 那是我们自己的（说话时张开）。
     *
     * 帧之间线性插值，首尾环绕（所以一个周期能无缝循环）。
     */
    fun frameAt(phase: Float, base: FloatArray = STAND): FloatArray {
        if (frames == 0) return base.copyOf()
        val x = ((phase % 1f) + 1f) % 1f * frames
        val i = x.toInt() % frames
        val j = (i + 1) % frames
        val t = x - x.toInt()
        val out = base.copyOf()
        for (k in 0 until 14) {
            val index = if (k < 9) k else k + 1
            val a = offsets[i].getOrElse(k) { 0f }
            val b = offsets[j].getOrElse(k) { 0f }
            out[index] = base[index] + a + (b - a) * t
        }
        return out
    }
}

/** 一份素材。解析不出来就是 null —— 那就退回手写的步态，不能让动作把 app 弄崩。 */
class GaitPack(val hz: Float, val clips: List<GaitClip>) {

    fun isEmpty(): Boolean = clips.isEmpty()

    /**
     * 按指令挑一段。现在只有"前进"一种，所以就是挑第一段方向对得上的；
     * 以后有转身/横移的素材时，这里换成"方向 + 速度最接近"。
     */
    fun forCommand(vx: Float, vy: Float, wz: Float): GaitClip? =
        clips.firstOrNull { it.matches(vx, vy, wz) }
}

/**
 * 解析 `gaits.json`。任何一处不对就返回 null（宁可没有步态，也不能让鸭子抽风）。
 */
fun parseGaitPack(text: String): GaitPack? = try {
    val root = JSONObject(text)
    val hz = root.optDouble("hz", 50.0).toFloat()
    val array = root.optJSONArray("clips")
    val clips = buildList {
        for (i in 0 until (array?.length() ?: 0)) {
            val c = array!!.getJSONObject(i)
            val frames = c.getJSONArray("offsets")
            val offsets = buildList {
                for (f in 0 until frames.length()) {
                    val row = frames.getJSONArray(f)
                    add(FloatArray(row.length()) { k -> row.optDouble(k, 0.0).toFloat() })
                }
            }
            if (offsets.isNotEmpty() && offsets.all { it.size >= 14 }) {
                add(
                    GaitClip(
                        name = c.optString("name", "clip"),
                        vx = c.optDouble("vx", 0.0).toFloat(),
                        vy = c.optDouble("vy", 0.0).toFloat(),
                        wz = c.optDouble("wz", 0.0).toFloat(),
                        hz = hz,
                        offsets = offsets,
                    ),
                )
            }
        }
    }
    if (clips.isEmpty()) null else GaitPack(hz, clips)
} catch (e: Exception) {
    null
}
