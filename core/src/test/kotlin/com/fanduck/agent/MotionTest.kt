package com.fanduck.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作的"活"：起停的加速/减速、步频跟速度走、待机呼吸、转身踏步。
 *
 * 这些不是规格 §10 那张表里的（那张表钉的是位置和姿态），是 2026-09-28 emin 说
 * "动作不太灵活 / 像滑行 / 像慢放"之后加的实现决定。规格 §6.3 里同步了这些常数和公式。
 */
class MotionTest {

    /** 让鸭子从静止走到 speed 并走稳，返回最后那一帧。 */
    private fun walk(speed: Float, ms: Long = 1_600): DuckMotion {
        val motion = DuckMotion()
        motion.sample(0)
        motion.velocity(speed, 0f, 0f)
        for (t in 0..ms step 10) motion.sample(t)
        return motion
    }

    /** 数 `joints[index]` 在窗口里的摆动峰数（一步一个峰）。 */
    private fun peaks(motion: DuckMotion, index: Int, from: Long, to: Long): Int {
        var count = 0
        var prev = 0f
        var prev2 = 0f
        var t = from
        while (t <= to) {
            val swing = motion.sample(t.toLong()).joints[index] - STAND[index]
            if (prev > prev2 && prev > swing) count++
            prev2 = prev
            prev = swing
            t += 10
        }
        return count
    }

    // ---------- 步频：慢走是"小步倒得密"，不是"大步慢放" ----------

    @Test
    fun `步频跟着速度走 两端和单调`() {
        assertEquals(STEP_HZ_MIN, stepHz(0f), 0.0001f)
        assertEquals(STEP_HZ_MAX, stepHz(1f), 0.0001f)
        assertEquals(STEP_HZ_MAX, stepHz(9f), 0.0001f)   // 超了也不再加
        assertTrue(stepHz(0.5f) > stepHz(0.2f))
        assertTrue(stepHz(0.2f) > stepHz(0f))
    }

    @Test
    fun `最慢的走也不是慢放`() {
        // 旧模型步频 = 1.5 × (速度/0.2)：0.05 m/s 就是 0.375 Hz —— 2.7 秒才迈一步，像慢动作回放。
        // 新模型最慢也有 STEP_HZ_MIN = 1.0 Hz。
        val steps = peaks(walk(0.05f, ms = 2_000), 2, 0, 2_000)
        assertTrue("2 秒里只摆了 $steps 次，太像慢放了", steps >= 2)
    }

    @Test
    fun `走得快 步子就倒得密`() {
        // 数峰会把游标一路推下去，所以每个实例只能数一次
        val fastPeaks = peaks(walk(0.2f, ms = 2_000), 2, 0, 2_000)
        val slowPeaks = peaks(walk(0.1f, ms = 2_000), 2, 0, 2_000)
        assertTrue("满速 $fastPeaks 步 应该比半速 $slowPeaks 步多", fastPeaks > slowPeaks)
    }

    // ---------- 起停的加速/减速 ----------

    @Test
    fun `起步往前探 刹车往后坐`() {
        assertEquals(0.0f, accelLean(0.2f, 0.2f), 0.0001f)    // 追上了就不倾
        assertTrue(accelLean(0.2f, 0f) > 0.05f)               // 刚起步：正
        assertTrue(accelLean(0f, 0.2f) < -0.05f)              // 刚停：负（往后坐）
        assertTrue("要限幅", accelLean(5f, 0f) <= 0.2f * LEAN_GAIN + 0.0001f)

        val motion = DuckMotion()
        motion.sample(0)
        motion.velocity(0.2f, 0f, 0f)
        // 下标 5（脖子）**变小 = 头往前**，方向是拿前向运动学量的，别凭感觉改
        val head = motion.sample(50).joints[5] - STAND[5]
        assertTrue("起步时头该往前探一点，实测 $head", head < -0.02f)
    }

    @Test
    fun `一步一点头`() {
        // 这个骨架没有身体起伏这个自由度（躯干是根节点），脚步的节奏只能靠头带出来
        val motion = walk(0.2f, ms = 1_600)
        var minNeck = Float.MAX_VALUE
        var maxNeck = -Float.MAX_VALUE
        for (t in 0..1_600 step 10) {
            val neck = motion.sample(t.toLong()).joints[5] - STAND[5]
            minNeck = minOf(minNeck, neck); maxNeck = maxOf(maxNeck, neck)
        }
        assertTrue("走路时头该跟着步子点，实测幅度 ${maxNeck - minNeck}", maxNeck - minNeck > 0.02f)
    }

    // ---------- 待机呼吸 ----------

    @Test
    fun `站着不动也有呼吸 但很轻`() {
        val idle = DuckMotion()
        var minNeck = Float.MAX_VALUE
        var maxNeck = -Float.MAX_VALUE
        for (t in 0..8_000 step 100) {
            val neck = idle.sample(t.toLong()).joints[5]
            minNeck = minOf(minNeck, neck); maxNeck = maxOf(maxNeck, neck)
        }
        val swing = maxNeck - minNeck
        assertTrue("站着完全不动就不像活的，实测 $swing", swing > 0.02f)
        assertTrue("但呼吸不该看得出来，实测 $swing", swing < 0.15f)
    }

    @Test
    fun `呼吸在 t=0 时是 0 不污染站立姿态`() {
        // §10 断言 10（sample(0) 就是 STAND）要求这个
        val joints = DuckMotion().sample(0).joints
        for (i in 0 until 15) assertEquals(STAND[i], joints[i], 0.0001f)
    }

    // ---------- 原地转身：踏步，不是转盘 ----------

    @Test
    fun `原地转身要踏步 而且髋不那么摆`() {
        val turn = DuckMotion()
        turn.sample(0)
        turn.velocity(0f, 0f, 1f)
        var maxLift = 0f
        var maxHip = 0f
        for (t in 0..2_000 step 10) {
            val f = turn.sample(t.toLong()).joints
            maxLift = maxOf(maxLift, f[3] - STAND[3])
            maxHip = maxOf(maxHip, kotlin.math.abs(f[2] - STAND[2]))
        }
        val walking = walk(0.2f, ms = 2_000)
        var walkHip = 0f
        for (t in 0..2_000 step 10) {
            walkHip = maxOf(walkHip, kotlin.math.abs(walking.sample(t.toLong()).joints[2] - STAND[2]))
        }
        assertTrue("转身时该抬腿（实测 $maxLift）", maxLift > 0.25f)
        assertTrue("转身时髋不该像走路那样前后甩（实测 $maxHip，走路 $walkHip）", maxHip < walkHip * 0.5f)
    }
}
