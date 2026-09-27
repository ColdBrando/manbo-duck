package com.fanduck.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静音窗口的断言。规格 §10 那张表里没有这一条（它是接语音时补的，见 `MuteWindow`），
 * 所以单独一个文件，不和 §10 的"一行一条"混在一起。
 */
class MuteWindowTest {

    @Test
    fun `没开口过就不静音`() {
        assertFalse(MuteWindow().muted(0))
        assertFalse(MuteWindow().muted(999_999))
    }

    @Test
    fun `说话期间一直静音`() {
        val w = MuteWindow()
        w.noteSpeaking(1_000, on = true)
        assertTrue(w.muted(1_000))
        assertTrue(w.muted(1_001))
        assertTrue(w.muted(9_999_999))   // 还没说完，多久都静音
    }

    @Test
    fun `说完之后 800 ms 内仍然静音，之后放行`() {
        val w = MuteWindow()
        w.noteSpeaking(1_000, on = true)
        w.noteSpeaking(5_000, on = false)
        assertTrue(w.muted(5_000))
        assertTrue(w.muted(5_799))
        assertFalse(w.muted(5_800))      // tail 是 800 ms
    }

    @Test
    fun `尾巴没过完又开口，窗口从新的开口重新算`() {
        val w = MuteWindow()
        w.noteSpeaking(1_000, on = false)
        w.noteSpeaking(1_500, on = true)
        assertTrue(w.muted(1_600))
        w.noteSpeaking(2_000, on = false)
        assertFalse(w.muted(2_800))
    }

    @Test
    fun `尾巴可以按需调短`() {
        val w = MuteWindow(tailMs = 100)
        w.noteSpeaking(0, on = false)
        assertTrue(w.muted(99))
        assertFalse(w.muted(100))
    }
}
