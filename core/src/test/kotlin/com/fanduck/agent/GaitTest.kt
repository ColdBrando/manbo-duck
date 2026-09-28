package com.fanduck.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真机训出来的步态怎么进到屏幕鸭子上：素材解析、按相位取帧、挑片段、
 * 以及"指令太小就站着"这条和真机一致的行为。
 */
class GaitTest {

    /** 一个特征明显的假片段：左膝（下标 3）在 ±0.3 之间摆，四帧一循环。 */
    private fun fakeClip(frames: Int = 4, swing: Float = 0.3f) = GaitClip(
        name = "fake",
        vx = 0.4f, vy = 0f, wz = 0f,
        hz = 50f,
        offsets = List(frames) { f ->
            FloatArray(14) { k -> if (k == 3) (if (f % 2 == 0) swing else -swing) else 0f }
        },
    )

    private fun pack(vararg clips: GaitClip) = GaitPack(50f, clips.toList())

    // ---------- 素材本身 ----------

    @Test
    fun `仓库里烘出来的素材能解析`() {
        // 这个文件是 tools/duckgait/bake_gait.py 从真机策略的录制里烘的，坏了就是真坏了
        val file = File("../app/src/main/assets/duck/gaits.json")
        assertTrue("找不到 ${file.absolutePath}，先跑 bake_gait.py", file.isFile)
        val gaitPack = parseGaitPack(file.readText())
        assertNotNull("素材解析不出来", gaitPack)
        val clip = gaitPack!!.clips.first()
        assertEquals("walk", clip.name)
        assertEquals("一个周期 20 帧 @50 Hz = 400 ms = 2.5 Hz", 20, clip.frames)
        assertEquals(400f, clip.periodMs, 1f)
        assertEquals(0.4f, clip.vx, 0.001f)
        // 训练出来的步态里，膝盖和头都在动（头是策略自己算的配平）
        val knee = clip.offsets.map { it[3] }
        val headYaw = clip.offsets.map { it[7] }
        assertTrue("膝盖该摆起来", knee.max() - knee.min() > 0.3f)
        assertTrue("头该跟着摆", headYaw.max() - headYaw.min() > 0.1f)
    }

    @Test
    fun `坏数据解析成 null 而不是崩`() {
        assertNull(parseGaitPack("{ 这不是 json"))
        assertNull(parseGaitPack("""{"hz":50,"clips":[]}"""))
        assertNull(parseGaitPack("""{"hz":50,"clips":[{"name":"x","offsets":[]}]}"""))
    }

    // ---------- 按相位取帧 ----------

    @Test
    fun `相位 0 是第一帧 偏移落在对的下标上`() {
        val frame = fakeClip().frameAt(0f)
        assertEquals(STAND[3] + 0.3f, frame[3], 0.0001f)      // 左膝拿到偏移
        assertEquals("嘴（下标 9）不归素材管", STAND[9], frame[9], 0.0001f)
        assertEquals("右腿该在右腿的位置上（跳过嘴）", STAND[12], frame[12], 0.0001f)
    }

    @Test
    fun `循环处接得上`() {
        val clip = fakeClip()
        val a = clip.frameAt(0f)
        val b = clip.frameAt(1f)
        for (i in 0 until 15) assertEquals("相位 1 应该等于相位 0", a[i], b[i], 0.0001f)
    }

    @Test
    fun `帧之间是插值不是跳变`() {
        val clip = fakeClip(frames = 4, swing = 0.4f)
        // 四帧：+0.4 -0.4 +0.4 -0.4，相位 0.125 落在第 0、1 帧正中间
        val mid = clip.frameAt(0.125f)
        assertEquals(STAND[3], mid[3], 0.0001f)
    }

    // ---------- 挑片段 ----------

    @Test
    fun `前进指令挑得中 后退挑不中`() {
        val gaitPack = pack(fakeClip())
        assertNotNull(gaitPack.forCommand(0.4f, 0f, 0f))
        assertNull("录像只有前进，别拿它去播后退", gaitPack.forCommand(-0.4f, 0f, 0f))
    }

    // ---------- 接进 DuckMotion ----------

    @Test
    fun `走起来就用素材的步态`() {
        val motion = DuckMotion()
        motion.loadGaits(pack(fakeClip(swing = 0.3f)))
        motion.sample(0)
        motion.velocity(0.4f, 0f, 0f)
        val knee = mutableListOf<Float>()
        for (t in 0..2000 step 20) knee += motion.sample(t.toLong()).joints[3]
        val swing = knee.max() - knee.min()
        assertTrue("左膝该跟着素材摆（实测 $swing）", swing > 0.4f)
    }

    @Test
    fun `慢走也用真步态 不是退回手写的那套`() {
        // 真机在 0.3 以下是站着的（它要保平衡）；屏幕鸭子没这个问题，
        // 而且站着不迈腿却照常位移就是滑行 —— 所以只要在走就用真步态。
        val motion = DuckMotion()
        motion.loadGaits(pack(fakeClip(swing = 0.3f)))
        motion.sample(0)
        motion.velocity(0.1f, 0f, 0f)
        val knee = mutableListOf<Float>()
        for (t in 0..2000 step 20) knee += motion.sample(t.toLong()).joints[3]
        val swing = knee.max() - knee.min()
        assertTrue("慢走也该迈腿（实测摆动 $swing）", swing > 0.4f)
    }

    @Test
    fun `停下来就不迈腿了`() {
        val motion = DuckMotion()
        motion.loadGaits(pack(fakeClip(swing = 0.3f)))
        motion.sample(0)
        val knee = mutableListOf<Float>()
        for (t in 0..2000 step 20) knee += motion.sample(t.toLong()).joints[3]
        val swing = knee.max() - knee.min()
        assertTrue("站着不动就该站着（实测摆动 $swing）", swing < 0.05f)
    }

    @Test
    fun `没有素材也能走 手写步态是兜底`() {
        val motion = DuckMotion()
        motion.sample(0)
        motion.velocity(0.4f, 0f, 0f)
        val knee = mutableListOf<Float>()
        for (t in 0..2000 step 20) knee += motion.sample(t.toLong()).joints[3]
        assertTrue("手写步态也该摆腿", knee.max() - knee.min() > 0.3f)
    }

    @Test
    fun `切进素材是渐入的 不是一帧跳过去`() {
        val motion = DuckMotion()
        motion.loadGaits(pack(fakeClip(frames = 2, swing = 0.5f)))   // 两帧：最跳的那种
        motion.sample(0)
        motion.velocity(0.4f, 0f, 0f)
        // 20 ms 后（远小于 200 ms 的过渡）还不该到满幅
        val early = kotlin.math.abs(motion.sample(20).joints[3] - STAND[3])
        assertTrue("刚起步就跳到素材的幅度会很突兀（实测 $early）", early < 0.4f)
    }

    @Test
    fun `素材的周期按自己的帧数算 不是手写步频`() {
        // 手写步频是 1.0~2.6 Hz（stepHz）；素材是 20 帧 @50 Hz = 2.5 Hz，各走各的
        assertEquals(400f, fakeClip(frames = 20).periodMs, 0.01f)
        assertEquals(80f, fakeClip(frames = 4).periodMs, 0.01f)
    }
}
