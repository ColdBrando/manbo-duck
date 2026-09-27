package com.fanduck.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 规格 §4.1 那 4 条规则的断言。§10 那张表里没有它们（表里只看「看到」进了意图），
 * 所以单独一个文件。
 */
class SenseLoopTest {

    private class FixedSense(private val caption: String, private var distance: Float?) : SensePort {
        override fun captionAndDistance(): Pair<String, Float?> = caption to distance
        fun distance(meters: Float?) { distance = meters }
    }

    private fun state(sense: SensePort) = AgentState(
        cloud = object : CloudClient {
            override fun complete(messages: List<Map<String, String>>) = ""
        },
        robot = object : RobotPort {
            override fun padActive() = false
            override fun stop() = Ack.OK
            override fun velocity(vx: Float, vy: Float, wz: Float) = Ack.OK
            override fun gaze(yaw: Float, pitch: Float) = Ack.OK
            override fun stand() = Ack.OK
            override fun sit() = Ack.OK
            override fun say(text: String) = Unit
        },
        sense = sense,
        log = MemoryEpisodeSink { "2026-09-27T16:20:12+08:00" },
        now = { "2026-09-27T16:20:12+08:00" },
    )

    @Test
    fun `还没有 seen 的时候，第一拍就写一条`() {
        val state = state(FixedSense(NO_DETECTOR_CAPTION, null))
        val written = senseOnce(state)
        assertNotNull(written)
        assertEquals(NO_DETECTOR_CAPTION, written?.text)
        assertEquals(1, state.episodes.count { it.kind == Kind.SEEN })
    }

    @Test
    fun `文字没变就不写`() {
        val state = state(FixedSense(NO_DETECTOR_CAPTION, null))
        senseOnce(state)
        assertNull(senseOnce(state))
        assertNull(senseOnce(state))
        assertEquals(1, state.episodes.count { it.kind == Kind.SEEN })
    }

    @Test
    fun `距离变了就再写一条，拼上最近距离`() {
        val sense = FixedSense(NO_DETECTOR_CAPTION, 0.8f)
        val state = state(sense)
        assertEquals("$NO_DETECTOR_CAPTION，最近距离 0.8 米", senseOnce(state)?.text)
        sense.distance(0.3f)
        assertEquals("$NO_DETECTOR_CAPTION，最近距离 0.3 米", senseOnce(state)?.text)
        assertEquals(2, state.episodes.count { it.kind == Kind.SEEN })
    }

    @Test
    fun `不写的那一拍不去取 media（也就不会动磁盘）`() {
        val sense = FixedSense(NO_DETECTOR_CAPTION, null)
        val state = state(sense)
        var calls = 0
        senseOnce(state) { calls++; "seen/latest.jpg" }
        assertEquals(1, calls)
        senseOnce(state) { calls++; "seen/latest.jpg" }
        assertEquals("第二拍文字没变，不该再取 media", 1, calls)
    }
}
