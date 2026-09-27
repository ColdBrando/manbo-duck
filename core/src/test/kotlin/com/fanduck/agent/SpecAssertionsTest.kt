package com.fanduck.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 规格 §10「必须通过的断言」。一条 @Test 对应表格里的一行，行号写在测试名开头。
 * 全部在 JVM 上跑，不打开摄像头、麦克风、网络或 WebView。
 */
class SpecAssertionsTest {

    private companion object {
        const val NOW = "2026-09-27T16:20:12+08:00"
        const val TWO_MIN_AGO = "2026-09-27T16:18:12+08:00"
        const val FINAL_EMPTY = "Thought: 完成\nFinal Answer: {\"say\":\"好\",\"actions\":[]}"
    }

    // ---------- 测试替身 ----------

    private class FakeCloud(private val script: List<String> = emptyList()) : CloudClient {
        val requests = mutableListOf<List<Map<String, String>>>()
        override fun complete(messages: List<Map<String, String>>): String {
            requests += messages
            if (script.isEmpty()) return FINAL_EMPTY
            return script[minOf(requests.size - 1, script.size - 1)]
        }
    }

    private class Vel(val vx: Float, val vy: Float, val wz: Float)

    private class RecordingRobot : RobotPort {
        val sayCalls = mutableListOf<String>()
        val velocityCalls = mutableListOf<Vel>()
        val gazeCalls = mutableListOf<Pair<Float, Float>>()
        val named = mutableListOf<String>()
        var pad = false
        override fun padActive() = pad
        override fun stop() { named += "stop" }
        override fun velocity(vx: Float, vy: Float, wz: Float) { velocityCalls += Vel(vx, vy, wz) }
        override fun gaze(yaw: Float, pitch: Float) { gazeCalls += yaw to pitch }
        override fun stand() { named += "stand" }
        override fun sit() { named += "sit" }
        override fun say(text: String) { sayCalls += text }
    }

    private class FakeSense(
        private val caption: String = NO_DETECTOR_CAPTION,
        private val distance: Float? = null,
    ) : SensePort {
        var calls = 0
        override fun captionAndDistance(): Pair<String, Float?> {
            calls++
            return caption to distance
        }
    }

    private fun state(
        cloud: CloudClient,
        robot: RobotPort = RecordingRobot(),
        sense: SensePort = FakeSense(),
        episodes: List<Episode> = emptyList(),
    ) = AgentState(
        cloud = cloud,
        robot = robot,
        sense = sense,
        log = MemoryEpisodeSink { NOW },
        episodes = episodes.toMutableList(),
        now = { NOW },
        sleep = {},
    )

    private fun didTexts(state: AgentState): List<String> =
        state.episodes.filter { it.kind == Kind.DID }.map { it.text }

    // ---------- §10 断言表 ----------

    @Test
    fun `1 tokenize 过来一下`() {
        assertEquals(listOf("过", "来", "一", "下"), tokenize("过来一下"))
    }

    @Test
    fun `2 tokenize table3 是一个 token`() {
        assertEquals(listOf("table3"), tokenize("table3"))
    }

    @Test
    fun `3 第 3_1 节两分钟例子 score 等于 0_98125`() {
        val episode = Episode("seen-1", TWO_MIN_AGO, Kind.SEEN, "过来一下")
        val query = tokenize("过来")
        assertEquals(1f, keywordScore(query, tokenize(episode.text)), 0.0001f)
        assertEquals(0.9375f, recency(ageMinutes(NOW, TWO_MIN_AGO)), 0.0001f)
        assertEquals(0.98125f, score(query, episode, NOW), 0.0001f)
    }

    @Test
    fun `4 onHeard 啊 只记录不推理`() {
        val cloud = FakeCloud()
        val st = state(cloud)
        onHeard("啊", st)
        assertEquals(1, st.episodes.count { it.kind == Kind.HEARD })
        assertFalse(st.busy)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun `5 onHeard 过来 的意图里有原话`() {
        val cloud = FakeCloud()
        val st = state(cloud)
        onHeard("过来", st)
        assertEquals(1, st.episodes.count { it.kind == Kind.HEARD })
        assertEquals(1, cloud.requests.size)
        val lastUser = cloud.requests.last().last { it["role"] == "user" }["content"]!!
        assertTrue(lastUser.contains("语音：过来"))
    }

    @Test
    fun `6 busy 时再 onHeard 只入队`() {
        val cloud = FakeCloud()
        val st = state(cloud)
        st.busy = true
        onHeard("过来", st)
        assertEquals(0, cloud.requests.size)
        assertEquals(1, st.queue.size)
    }

    @Test
    fun `7 只有一条无关 seen 时 意图里仍有它`() {
        val seen = Episode("seen-1", TWO_MIN_AGO, Kind.SEEN, NO_DETECTOR_CAPTION)
        val intent = buildIntent("过来", listOf(seen), NOW)
        assertTrue(intent.contains(NO_DETECTOR_CAPTION))
    }

    @Test
    fun `8 vx 等于 1 时发出去的是 0_2`() {
        val cloud = FakeCloud(
            listOf("Thought: 走\nFinal Answer: {\"say\":\"走\",\"actions\":[{\"name\":\"velocity\",\"vx\":1,\"ms\":300}]}"),
        )
        val robot = RecordingRobot()
        val st = state(cloud, robot)
        onHeard("过来", st)
        assertEquals(1, robot.velocityCalls.size)
        assertEquals(0.2f, robot.velocityCalls[0].vx, 0.0001f)
        assertTrue(didTexts(st).any { it.contains("ok") })
    }

    @Test
    fun `9 jump 是未知动作`() {
        val cloud = FakeCloud(
            listOf("Thought: 跳\nFinal Answer: {\"say\":\"跳\",\"actions\":[{\"name\":\"jump\"}]}"),
        )
        val robot = RecordingRobot()
        val st = state(cloud, robot)
        onHeard("过来", st)
        assertTrue(robot.velocityCalls.isEmpty())
        assertTrue(didTexts(st).any { it.contains("未知动作") })
    }

    @Test
    fun `10 新建 DuckMotion sample 0 等于 STAND`() {
        val frame = DuckMotion().sample(0)
        for (i in 0 until 15) assertEquals(STAND[i], frame.joints[i], 0.0001f)
        assertEquals(0f, frame.x, 0.0001f)
        assertEquals(0f, frame.z, 0.0001f)
    }

    @Test
    fun `11 sit 后第 1 秒的下标 2`() {
        val motion = DuckMotion()
        motion.sample(0)
        motion.sit()
        assertEquals(-0.82895f, motion.sample(1000).joints[2], 0.0001f)
    }

    @Test
    fun `12 pitchSwing 二分之派`() {
        assertEquals(0.4f, pitchSwing((Math.PI / 2).toFloat(), 1f), 0.0001f)
    }

    @Test
    fun `13 velocity 走 1 秒 z 约 0_2`() {
        val motion = DuckMotion()
        motion.sample(0)
        motion.velocity(0.2f, 0f, 0f)
        var frame = motion.sample(0)
        for (t in 50..1000 step 50) frame = motion.sample(t.toLong())
        assertEquals(0.2f, frame.z, 0.01f)
    }

    @Test
    fun `14 stop 之后 z 不再变化`() {
        val motion = DuckMotion()
        motion.sample(0)
        motion.velocity(0.2f, 0f, 0f)
        var frame = motion.sample(0)
        for (t in 50..1000 step 50) frame = motion.sample(t.toLong())
        val zAtStop = frame.z
        motion.stop()
        for (t in 1050..2000 step 50) frame = motion.sample(t.toLong())
        assertEquals(zAtStop, frame.z, 0.001f)
    }

    @Test
    fun `15 gaze 45 度后下标 7`() {
        val motion = DuckMotion()
        motion.sample(0)
        motion.gaze(45f, 0f)
        assertEquals(45f * Math.PI.toFloat() / 180f, motion.sample(50).joints[7], 0.0001f)
    }

    @Test
    fun `16 张嘴三步到 0_6`() {
        val motion = DuckMotion()
        motion.sample(0)
        motion.setSpeaking(true)
        motion.sample(50)
        motion.sample(100)
        assertEquals(0.6f, motion.sample(150).joints[9], 0.0001f)
    }

    @Test
    fun `17 距离 0_2 米时不给前进速度 但照样说话`() {
        val cloud = FakeCloud(
            listOf("Thought: 走\nFinal Answer: {\"say\":\"我来了\",\"actions\":[{\"name\":\"velocity\",\"vx\":0.2,\"ms\":500}]}"),
        )
        val robot = RecordingRobot()
        val seen = Episode("seen-9", TWO_MIN_AGO, Kind.SEEN, "最近距离 0.2 米")
        val st = state(cloud, robot, episodes = listOf(seen))
        onHeard("过来", st)
        assertTrue(robot.velocityCalls.isEmpty())
        assertTrue(robot.sayCalls.contains("我来了"))
        assertTrue(didTexts(st).any { it.contains("距离过近") })
    }

    @Test
    fun `18 同时有 Action 和 Final Answer 时是 Final`() {
        val raw = "Thought: 我看看\nAction: look_now\nAction Input: {}\nFinal Answer: {\"say\":\"好\",\"actions\":[]}"
        assertTrue(parseReact(raw) is ReactStep.Final)
        // 并且真的不执行工具
        val sense = FakeSense()
        val st = state(FakeCloud(listOf(raw)), sense = sense)
        onHeard("过来", st)
        assertEquals(0, sense.calls)
    }

    @Test
    fun `19 先 look_now 的第二次请求里有 Observation`() {
        val cloud = FakeCloud(
            listOf(
                "Thought: 先看看\nAction: look_now\nAction Input: {}",
                "Thought: 够了\nFinal Answer: {\"say\":\"看到了\",\"actions\":[]}",
            ),
        )
        val sense = FakeSense(NO_DETECTOR_CAPTION, 0.8f)
        val st = state(cloud, sense = sense)
        onHeard("过来", st)
        assertEquals(2, cloud.requests.size)
        assertEquals(1, sense.calls)
        val second = cloud.requests[1]
        assertTrue(second.any { it["content"]!!.contains("Observation") })
        assertFalse(second.any { it["content"]!!.contains("FF D8") })
        assertFalse(second.any { it["content"]!!.contains("conversation_id") })
    }

    @Test
    fun `20 十条消息压缩后 keep 六条 drop 四条`() {
        val messages = (1..10).map { TranscriptMessage("user", "第 $it 条") }
        val (drop, keep) = splitForCompact(Transcript(messages = messages))
        assertEquals(4, drop.size)
        assertEquals(6, keep.size)
        assertEquals("第 4 条", drop.last().content)
        assertEquals("第 5 条", keep.first().content)
    }

    // ---------- 规格里没进断言表、但实现里做了决定的部分 ----------

    @Test
    fun `8 步用完就不再请求第九次`() {
        val cloud = FakeCloud(listOf("Thought: 再想想\nAction: look_now\nAction Input: {}"))
        val robot = RecordingRobot()
        val st = state(cloud, robot)
        onHeard("过来", st)
        assertEquals(MAX_REACT_STEPS, cloud.requests.size)
        assertTrue(cloud.requests.last().last()["content"]!!.contains("已达步数上限"))
        assertTrue(robot.sayCalls.contains(GIVE_UP_SAY))
    }

    @Test
    fun `距离文本写进去还能读回来`() {
        assertEquals("0.8", formatMeters(0.8f))
        assertEquals("1", formatMeters(1f))
        assertEquals(0.2f, nearestMeters(composeSeenText(NO_DETECTOR_CAPTION, 0.2f))!!, 0.0001f)
        assertEquals(0.8f, nearestMeters("摄像头正常，最近距离 0.8 米")!!, 0.0001f)
        assertEquals(null, nearestMeters(NO_DETECTOR_CAPTION))
    }

    @Test
    fun `看到文字没变就不再写`() {
        val last = Episode("seen-1", TWO_MIN_AGO, Kind.SEEN, NO_DETECTOR_CAPTION)
        assertTrue(shouldWriteSeen("有变化", last))
        assertFalse(shouldWriteSeen(NO_DETECTOR_CAPTION, last))
        assertTrue(shouldWriteSeen(NO_DETECTOR_CAPTION, null))
    }

    @Test
    fun `episodes jsonl 往返 坏行跳过 小写 kind`() {
        val file = File.createTempFile("episodes", ".jsonl")
        file.delete()
        try {
            val log = EpisodeLog(file) { NOW }
            log.append(Kind.HEARD, "过来")
            log.append(Kind.SEEN, "x".repeat(300))
            file.appendText("{坏行\n")
            val rows = log.read()
            assertEquals(2, rows.size)
            assertEquals("过来", rows[0].text)
            assertEquals(Kind.SEEN, rows[1].kind)
            assertEquals(SEEN_MAX_CHARS, rows[1].text.length)
            assertTrue(file.readLines()[0].contains("\"kind\":\"heard\""))
        } finally {
            file.delete()
        }
    }
}
