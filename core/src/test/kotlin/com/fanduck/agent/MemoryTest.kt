package com.fanduck.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 长期记忆：经历 → 事实的巩固、去重、装不下时丢谁、以及它怎么进意图。
 */
class MemoryTest {

    private companion object {
        const val NOW = "2026-09-28T02:00:00+08:00"
        const val EARLIER = "2026-09-28T01:00:00+08:00"
    }

    private fun event(id: String, at: String, kind: Kind, text: String) =
        Episode(id, at, kind, text)

    private fun fact(id: String, at: String, text: String) = Episode(id, at, Kind.FACT, text)

    // ---------- 什么时候值得巩固 ----------

    @Test
    fun `攒不够就不巩固`() {
        val events = (1..5).map { event("heard-$it", NOW, Kind.HEARD, "第 $it 句") }
        assertTrue(eventsToConsolidate(events, "", minCount = 40).isEmpty())
        assertEquals(5, eventsToConsolidate(events, "", minCount = 5).size)
    }

    @Test
    fun `只取水位之后的`() {
        val events = listOf(
            event("heard-1", EARLIER, Kind.HEARD, "旧的"),
            event("heard-2", NOW, Kind.HEARD, "新的"),
        )
        val batch = eventsToConsolidate(events, EARLIER, minCount = 1)
        assertEquals(listOf("新的"), batch.map { it.text })
    }

    @Test
    fun `看到和事实都不参与巩固`() {
        // seen 每 2 秒一条，混进去只会淹没有用的；fact 是产物不是原料
        val events = listOf(
            event("seen-1", NOW, Kind.SEEN, "摄像头正常"),
            event("fact-1", NOW, Kind.FACT, "用户叫 emin"),
            event("did-1", NOW, Kind.DID, "动作 stop 结果 ok"),
        )
        assertEquals(listOf("动作 stop 结果 ok"), eventsToConsolidate(events, "", minCount = 1).map { it.text })
    }

    @Test
    fun `巩固的话里带着经历和要求`() {
        val prompt = consolidationPrompt(listOf(event("heard-1", NOW, Kind.HEARD, "我叫 emin")))
        assertTrue(prompt.contains("我叫 emin"))
        assertTrue(prompt.contains(NOW))
        assertTrue(prompt.contains("$CONSOLIDATE_MAX_FACTS 条"))
        assertTrue("得说清楚只要 JSON 数组", prompt.contains("JSON"))
    }

    // ---------- 解析模型给的事实 ----------

    @Test
    fun `解析普通数组`() {
        assertEquals(
            listOf("用户叫 emin", "用户喜欢喝美式"),
            parseFacts("""["用户叫 emin", "用户喜欢喝美式"]"""),
        )
    }

    @Test
    fun `模型爱写的那些花样都能吃`() {
        // 代码块 + 前后废话 + 对象形式 + 空串 + 句号结尾
        val raw = """
            好的，这是值得记住的：
            ```json
            [{"text": "用户叫 emin。"}, "", "用户喜欢喝美式"]
            ```
        """.trimIndent()
        assertEquals(listOf("用户叫 emin", "用户喜欢喝美式"), parseFacts(raw))
    }

    @Test
    fun `没有值得记的就是空`() {
        assertTrue(parseFacts("[]").isEmpty())
        assertTrue(parseFacts("没有值得记的").isEmpty())
        assertTrue(parseFacts("[这不是 JSON").isEmpty())
        assertTrue(parseFacts("").isEmpty())
    }

    @Test
    fun `超过条数上限就截断`() {
        val raw = (1..20).joinToString(",", "[", "]") { "\"事实 $it\"" }
        assertEquals(CONSOLIDATE_MAX_FACTS, parseFacts(raw).size)
    }

    @Test
    fun `跟已有的重复就不再收`() {
        val existing = listOf(fact("fact-1", EARLIER, "用户叫 emin"))
        assertTrue(parseFacts("""["用户叫 emin"]""", existing).isEmpty())
        assertEquals(1, parseFacts("""["用户养了一只猫"]""", existing).size)
    }

    @Test
    fun `同一批里自己重复也只留一条`() {
        assertEquals(1, parseFacts("""["用户叫 emin", "用户叫 emin"]""").size)
    }

    // ---------- 合并与淘汰 ----------

    @Test
    fun `新的追加 重复的刷新时间`() {
        val existing = listOf(fact("fact-1", EARLIER, "用户叫 emin"))
        val merged = upsertFacts(existing, listOf("用户叫 emin", "用户养了一只猫"), NOW)
        assertEquals(2, merged.size)                                   // 没重复记
        assertEquals(NOW, merged.first { it.text == "用户叫 emin" }.at)  // 还活着：时间刷新了
        assertEquals("用户养了一只猫", merged.last().text)                // 新的在后面
    }

    @Test
    fun `装不下就丢最旧的 常提到的活得久`() {
        // "用户叫 emin" 老但一直提到（每轮都在刷新），"三年前的事" 老且再没出现
        val facts = listOf(
            fact("f1", EARLIER, "三年前的事"),
            fact("f2", EARLIER, "用户叫 emin"),
            fact("f3", NOW, "用户养了一只猫"),
        )
        val merged = upsertFacts(facts, listOf("用户叫 emin"), NOW, max = 2)
        assertEquals(2, merged.size)
        assertFalse("被丢的是最久没被提到的那条", merged.any { it.text == "三年前的事" })
        assertTrue(merged.any { it.text == "用户叫 emin" })
    }

    // ---------- 落盘 ----------

    @Test
    fun `事实落盘再读回来`() {
        val file = File.createTempFile("facts", ".jsonl")
        file.delete()
        try {
            val log = FactsLog(file)
            log.remember("用户叫 emin", NOW)
            log.remember("用户养了一只猫", NOW)
            val rows = log.read()
            assertEquals(2, rows.size)
            assertEquals(Kind.FACT, rows[0].kind)
            assertEquals("用户叫 emin", rows[0].text)
            assertTrue(file.readLines()[0].contains("\"kind\":\"fact\""))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `重复 remember 不涨条数 只刷新时间`() {
        val file = File.createTempFile("facts", ".jsonl")
        file.delete()
        try {
            val log = FactsLog(file)
            log.remember("用户叫 emin", EARLIER)
            val merged = log.remember("用户叫 emin", NOW)
            assertEquals(1, merged.size)
            assertEquals(NOW, merged[0].at)
            assertEquals(1, log.read().size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `巩固进度落盘再读回来`() {
        val file = File.createTempFile("memory-state", ".json")
        file.delete()
        try {
            assertEquals("", readMemoryState(file).consolidatedUpTo)   // 不存在就是空的
            writeMemoryState(file, MemoryState(NOW))
            assertEquals(NOW, readMemoryState(file).consolidatedUpTo)
        } finally {
            file.delete()
        }
    }

    // ---------- 进意图、进工具 ----------

    @Test
    fun `有事实时意图里有记得 没有就没有`() {
        val facts = listOf(fact("fact-1", EARLIER, "用户叫 emin"))
        val withFacts = buildIntent("你好", emptyList(), NOW, facts = facts)
        assertTrue(withFacts.contains("记得："))
        assertTrue(withFacts.contains("用户叫 emin"))
        assertFalse("没有事实时不该出现空块", buildIntent("你好", emptyList(), NOW).contains("记得："))
    }

    @Test
    fun `remember 是白名单里的工具 不认识的才是格式错误`() {
        // 踩过：parseReact 里那份硬编码白名单没加 remember，模型调了被当成格式错误，
        // 表现是"它说记住了其实没记"
        assertTrue(parseReact("Thought: 记\nAction: remember\nAction Input: {\"text\":\"x\"}") is ReactStep.Tool)
        assertTrue(parseReact("Thought: 找\nAction: search_memory\nAction Input: {}") is ReactStep.Tool)
        assertTrue(parseReact("Thought: 编\nAction: jump\nAction Input: {}") is ReactStep.Invalid)
        assertTrue(TOOL_NAMES.containsAll(setOf("search_memory", "look_now", "remember")))
    }

    @Test
    fun `模型调 remember 就记下来`() {
        val cloud = object : CloudClient {
            var calls = 0
            override fun complete(messages: List<Map<String, String>>): String {
                calls++
                return if (calls == 1) {
                    "Thought: 该记住\nAction: remember\nAction Input: {\"text\":\"用户叫 emin\"}"
                } else {
                    "Thought: 好\nFinal Answer: {\"say\":\"记住了\",\"actions\":[]}"
                }
            }
        }
        val saved = mutableListOf<List<Episode>>()
        val state = AgentState(
            cloud = cloud,
            robot = object : RobotPort {
                override fun padActive() = false
                override fun stop() = Ack.OK
                override fun velocity(vx: Float, vy: Float, wz: Float) = Ack.OK
                override fun gaze(yaw: Float, pitch: Float) = Ack.OK
                override fun stand() = Ack.OK
                override fun sit() = Ack.OK
                override fun say(text: String) = Unit
            },
            sense = object : SensePort {
                override fun captionAndDistance() = NO_DETECTOR_CAPTION to null
            },
            log = MemoryEpisodeSink { NOW },
            now = { NOW },
            sleep = {},
            remember = { text -> listOf(fact("fact-1", NOW, text)).also { saved += it } },
        )
        onHeard("我叫 emin", state)
        assertEquals(listOf("用户叫 emin"), state.facts.map { it.text })
        assertTrue(saved.isNotEmpty())
        assertNotNull(state.transcript.messages.lastOrNull { it.role == "tool" })
    }
}
