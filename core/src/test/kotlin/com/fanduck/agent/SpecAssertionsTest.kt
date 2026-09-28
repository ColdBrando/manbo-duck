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

        /** 要拒绝哪个动作就放进来（§12：真机的电机服务可以拒绝意图）。 */
        val refused = mutableMapOf<String, String>()

        private fun ack(name: String): Ack = refused[name]?.let { Ack.refused(it) } ?: Ack.OK

        override fun padActive() = pad
        override fun stop(): Ack {
            named += "stop"
            return ack("stop")
        }

        override fun velocity(vx: Float, vy: Float, wz: Float): Ack {
            velocityCalls += Vel(vx, vy, wz)
            return ack("velocity")
        }

        override fun gaze(yaw: Float, pitch: Float): Ack {
            gazeCalls += yaw to pitch
            return ack("gaze")
        }

        override fun stand(): Ack {
            named += "stand"
            return ack("stand")
        }

        override fun sit(): Ack {
            named += "sit"
            return ack("sit")
        }

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
        sleep: (Int) -> Unit = {},
        archive: (List<TranscriptMessage>) -> Unit = {},
    ) = AgentState(
        cloud = cloud,
        robot = robot,
        sense = sense,
        log = MemoryEpisodeSink { NOW },
        episodes = episodes.toMutableList(),
        now = { NOW },
        sleep = sleep,
        archive = archive,
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
        val slept = mutableListOf<Int>()
        val st = state(cloud, robot, sleep = { slept += it })
        onHeard("过来", st)
        assertEquals(1, robot.velocityCalls.size)
        assertEquals(0.2f, robot.velocityCalls[0].vx, 0.0001f)
        assertEquals(listOf(300), slept)   // 跑起来了才等这一步走完
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
    fun `13 velocity 走 1 秒 z 约 0_186（起步有加速 不是瞬时匀速）`() {
        val motion = DuckMotion()
        motion.sample(0)
        motion.velocity(0.2f, 0f, 0f)
        var frame = motion.sample(0)
        for (t in 50..1000 step 50) frame = motion.sample(t.toLong())
        // 2026-09-28（emin 授权）改成"起停有一阶滞后"之后，同样 0.2 m/s 走 1 秒走不到整整
        // 0.2 米：起步那 ACCEL_TAU 秒是在用时间换速度，少走的部分约等于 V·τ。
        // 具体值跟采样率有关（离散趋近），所以断言一个区间。
        assertTrue("z=${frame.z}，起步该吃掉一点", frame.z in 0.17f..0.199f)
    }

    @Test
    fun `14 stop 之后滑一小段就停住`() {
        val motion = DuckMotion()
        motion.sample(0)
        motion.velocity(0.2f, 0f, 0f)
        var frame = motion.sample(0)
        for (t in 50..1000 step 50) frame = motion.sample(t.toLong())
        val zAtStop = frame.z
        motion.stop()
        var z500 = zAtStop
        for (t in 1050..1500 step 50) z500 = motion.sample(t.toLong()).z
        val coast = z500 - zAtStop
        // 一阶滞后：停下来还会往前滑 V·τ 那么一小段 —— 那是"刹车"，不是漂移
        assertTrue("滑太远：$coast", coast in 0.002f..0.04f)
        // 但必须真的停住：半秒之后一点都不许再动
        var z1000 = z500
        for (t in 1550..2000 step 50) z1000 = motion.sample(t.toLong()).z
        assertEquals("半秒之后还在动", z500, z1000, 0.001f)
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

    // ---------- 评审留下的四条：改完在这里钉住 ----------

    @Test
    fun `打字进来记成 text，意图里写打字`() {
        val cloud = FakeCloud()
        val st = state(cloud)
        onTyped("过来", st)
        assertEquals(1, st.episodes.count { it.kind == Kind.TEXT })
        assertEquals(0, st.episodes.count { it.kind == Kind.HEARD })
        assertEquals(Kind.TEXT, kindOrNull("text"))   // 落盘后读得回来
        val lastUser = cloud.requests.last().last { it["role"] == "user" }["content"]!!
        assertTrue(lastUser.contains("打字：过来"))
        assertFalse(lastUser.contains("语音：过来"))
    }

    @Test
    fun `没打过字的时候 意图里没有打字块`() {
        // 语音是主链路：没碰过键盘时，意图和 §3.3 原样一样是四个块
        val seen = Episode("seen-1", TWO_MIN_AGO, Kind.SEEN, NO_DETECTOR_CAPTION)
        val intent = buildIntent("过来", listOf(seen), NOW)
        assertTrue(intent.contains("语音：过来"))
        assertFalse(intent.contains("打字："))
    }

    @Test
    fun `压缩请求失败时本轮照跑，也不归档`() {
        // 轨迹超过上限 → 每轮开始前先压缩。让**压缩那一次**调用抛异常（评审 P0-2）。
        val cloud = object : CloudClient {
            var compactions = 0
            override fun complete(messages: List<Map<String, String>>): String {
                if (messages.any { it["content"]!!.contains("收成不超过") }) {
                    compactions++
                    throw RuntimeException("压缩超时")
                }
                return FINAL_EMPTY
            }
        }
        val robot = RecordingRobot()
        val archived = mutableListOf<TranscriptMessage>()
        val st = AgentState(
            cloud = cloud,
            robot = robot,
            sense = FakeSense(),
            log = MemoryEpisodeSink { NOW },
            transcript = Transcript(
                messages = (1..40).map { TranscriptMessage("user", "第 $it 条" + "x".repeat(400)) },
            ),
            now = { NOW },
            sleep = {},
            archive = { archived += it },
        )
        onHeard("过来", st)                          // 异常不该穿出去
        assertEquals(1, cloud.compactions)
        assertTrue("没压成就不能归档", archived.isEmpty())
        assertTrue(robot.sayCalls.contains("好"))      // 本轮照样走完
        assertEquals(42, st.transcript.messages.size)  // 40 条原文 + 本轮意图 + 本轮回话
    }

    @Test
    fun `轮内压缩不把本轮意图切进 drop`() {
        // 一轮里走到第 8 条：user 意图 + 7 条工具往返。规格的裸 takeLast(6) 会把意图摘要掉。
        val messages = listOf(TranscriptMessage("user", "语音：过来")) +
            (1..7).map { TranscriptMessage("tool", "Observation: 第 $it 条") }
        val (drop, keep) = splitForCompact(Transcript(messages = messages))
        assertEquals(messages.size, drop.size + keep.size)   // 一条都不丢
        assertEquals("语音：过来", keep.first().content)        // 问题留在工作轨迹里
        assertTrue(drop.none { it.content == "语音：过来" })    // 没被拿去摘要
        assertEquals(COMPACT_KEEP_TAIL + 1, keep.size)
        assertEquals(messages[1], drop.single())              // 该进摘要的是更早的那条往返
    }

    @Test
    fun `真机拒绝意图时 did 记真原因，也不再空等`() {
        val cloud = FakeCloud(
            listOf("Thought: 走\nFinal Answer: {\"say\":\"走\",\"actions\":[{\"name\":\"velocity\",\"vx\":0.2,\"ms\":500}]}"),
        )
        val robot = RecordingRobot()
        robot.refused["velocity"] = "手柄占用"
        val slept = mutableListOf<Int>()
        val st = state(cloud, robot, sleep = { slept += it })
        onHeard("过来", st)
        assertEquals(1, robot.velocityCalls.size)                       // 意图确实发出去了
        assertEquals("被拒绝就不该等这一步", 0, slept.size)
        assertTrue(didTexts(st).any { it.contains("fail") && it.contains("手柄占用") })
        assertFalse(didTexts(st).any { it.endsWith("ok") })
    }
}
