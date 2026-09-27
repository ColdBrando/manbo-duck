package com.fanduck.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别源的挑选与连续听的节拍。都是纯的 —— 跑模型/接 SDK 的那半在 :app。
 */
class SttTest {

    private class Fake(
        override val id: String,
        override val onDevice: Boolean = false,
        override val available: Boolean = true,
    ) : SttSource {
        override val label: String = id
        override var onEvent: ((SttEvent) -> Unit)? = null
        override fun start() = Unit
        override fun stop() = Unit
        override fun cancel() = Unit
        override fun destroy() = Unit
    }

    private val onDevice = Fake("on-device", onDevice = true)
    private val system = Fake("system")

    // ---------- 挑一个 ----------

    @Test
    fun `端上优先于系统默认`() {
        // 顺序反过来也要挑端上：这一条是隐私，不是性能
        assertSame(onDevice, chooseStt(listOf(system, onDevice)))
    }

    @Test
    fun `点名要的那个优先 哪怕它不是端上`() {
        assertSame(system, chooseStt(listOf(system, onDevice), prefer = "system"))
    }

    @Test
    fun `点名的不可用就退回默认规则`() {
        val broken = Fake("iflytek", available = false)
        assertSame(onDevice, chooseStt(listOf(broken, system, onDevice), prefer = "iflytek"))
    }

    @Test
    fun `不能用的不进候选`() {
        val broken = Fake("system", available = false)
        assertSame(onDevice, chooseStt(listOf(broken, onDevice)))
    }

    @Test
    fun `一个能用的都没有就是 null`() {
        assertNull(chooseStt(listOf(Fake("a", available = false))))
        assertNull(chooseStt(emptyList()))
    }

    @Test
    fun `没有端上的时候就用系统默认`() {
        assertSame(system, chooseStt(listOf(system)))
    }

    // ---------- 换下一个 ----------

    @Test
    fun `换下一个 跳过当前和不能用的`() {
        val broken = Fake("iflytek", available = false)
        assertSame(system, nextStt(listOf(onDevice, broken, system), current = onDevice))
    }

    @Test
    fun `没得换就 null`() {
        assertNull(nextStt(listOf(onDevice), current = onDevice))
        assertNull(nextStt(listOf(onDevice, Fake("x", available = false)), current = onDevice))
    }

    @Test
    fun `两个源都判了死刑就没得换 不会来回换`() {
        // 模拟器上就是这么演了一遍：on-device 报 12（不支持中文）→ 换 system → system 也报 12。
        // 不拉黑的话这两个会互相换来换去，换到天荒地老。
        val dead = setOf(onDevice.id, system.id)
        assertNull(nextStt(listOf(onDevice, system), current = system, dead = dead))
        assertNull(chooseStt(listOf(onDevice, system), dead = dead))
    }

    @Test
    fun `判死刑的源不再被挑中 但另一个还在`() {
        assertSame(system, chooseStt(listOf(onDevice, system), dead = setOf(onDevice.id)))
        assertSame(system, nextStt(listOf(onDevice, system), current = onDevice, dead = setOf(onDevice.id)))
    }

    // ---------- 哪种错误该换源 ----------

    @Test
    fun `语言不支持算这个源的死刑 别的算这一轮不行`() {
        assertTrue("12 = 语言不支持", fatalForSource(12))
        assertTrue("13 = 语言数据没下", fatalForSource(13))
        assertFalse("2 = 网络（模拟器上那个）", fatalForSource(2))
        assertFalse("6 = 静默超时", fatalForSource(6))
        assertFalse("7 = 没听清", fatalForSource(7))
    }

    // ---------- 错误码 ----------

    @Test
    fun `错误码翻成人话 认不出来就报数字`() {
        assertEquals("网络不通（连不上识别服务）", sttErrorText(2))
        assertEquals("没听清", sttErrorText(7))
        assertEquals("中文语言数据没下载", sttErrorText(13))
        assertEquals("错误码 99", sttErrorText(99))
    }

    // ---------- 连续听的节拍 ----------

    @Test
    fun `正常一轮之后按 restartMs 重挂`() {
        val backoff = ListenBackoff(restartMs = 200, minCycleMs = 1_000)
        assertEquals(200L, backoff.onCycleEnded(startedAt = 0, endedAt = 5_200))
        assertFalse(backoff.backingOff)
    }

    @Test
    fun `秒回要退避 翻倍到上限为止`() {
        val backoff = ListenBackoff(restartMs = 200, minCycleMs = 1_000, maxBackoffMs = 5_000)
        // 一轮只撑了 30 ms：识别服务不可用时就是这样
        assertEquals(400L, backoff.onCycleEnded(0, 30))
        assertEquals(800L, backoff.onCycleEnded(1_000, 1_030))
        assertEquals(1_600L, backoff.onCycleEnded(2_000, 2_030))
        assertEquals(3_200L, backoff.onCycleEnded(3_000, 3_030))
        assertEquals(5_000L, backoff.onCycleEnded(4_000, 4_030))   // 到了上限就不再翻
        assertEquals(5_000L, backoff.onCycleEnded(5_000, 5_030))
        assertTrue(backoff.backingOff)
    }

    @Test
    fun `恢复正常之后退避收回`() {
        val backoff = ListenBackoff(restartMs = 200, minCycleMs = 1_000, maxBackoffMs = 5_000)
        backoff.onCycleEnded(0, 30)          // 退到 400
        assertEquals(400L, backoff.currentMs)
        assertEquals(200L, backoff.onCycleEnded(1_000, 6_200))   // 这一轮撑了 5.2 秒
        assertFalse(backoff.backingOff)
    }

    @Test
    fun `刚好到 minCycleMs 不算秒回`() {
        val backoff = ListenBackoff(restartMs = 200, minCycleMs = 1_000)
        assertEquals(200L, backoff.onCycleEnded(0, 1_000))
    }
}
