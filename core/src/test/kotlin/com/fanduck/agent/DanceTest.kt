package com.fanduck.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 《哈基米》舞步的断言。规格 §10 那张表里没有它（跳舞是后加的功能，见 §13），
 * 所以单独一个文件。这里只测纯的部分：循环长度、关键帧取值、插值、两端夹住、左右镜像。
 */
class DanceTest {

    private val keys = hakimiDance()

    @Test
    fun `八拍一循环，最后一帧接回第一帧`() {
        assertEquals(9, keys.size)
        assertEquals(HAKIMI_BEAT_MS * 8, danceLoopMs(keys))
        assertEquals(0L, keys.first().atMs)
        assertTrue(keys.first().joints.contentEquals(keys.last().joints))
        assertEquals(keys.first().x, keys.last().x, 1e-6f)
    }

    @Test
    fun `关键帧上取到的就是那一帧`() {
        for (key in keys) {
            val frame = danceFrame(keys, key.atMs)
            assertTrue(
                "第 ${key.atMs} ms 的姿势应该等于关键帧",
                frame.joints.contentEquals(key.joints),
            )
            assertEquals(key.x, frame.x, 1e-6f)
            assertEquals(key.yaw, frame.yaw, 1e-6f)
        }
    }

    @Test
    fun `两个关键帧之间是插值，落在两端之间`() {
        val mid = danceFrame(keys, keys[0].atMs + HAKIMI_BEAT_MS / 2)
        // 第 0 拍抬右腿、第 1 拍落地：膝角应该在两者之间
        val kneeAt0 = keys[0].joints[13]
        val kneeAt1 = keys[1].joints[13]
        assertTrue(mid.joints[13] != kneeAt0 && mid.joints[13] != kneeAt1)
        assertTrue(mid.joints[13] in minOf(kneeAt0, kneeAt1)..maxOf(kneeAt0, kneeAt1))
        // x 也是插出来的
        assertTrue(mid.x in minOf(keys[0].x, keys[1].x)..maxOf(keys[0].x, keys[1].x))
    }

    @Test
    fun `两头夹住`() {
        assertTrue(danceFrame(keys, -5_000).joints.contentEquals(keys.first().joints))
        assertTrue(danceFrame(keys, 999_999).joints.contentEquals(keys.last().joints))
        assertTrue(danceFrame(emptyList(), 0).joints.contentEquals(STAND))
    }

    @Test
    fun `落地那几拍左右腿是镜像的`() {
        for (index in intArrayOf(1, 3, 5)) {
            val j = keys[index].joints
            assertEquals(
                "第 $index 拍的髋侧摆应该左右反号",
                j[1] - STAND[1],
                -(j[11] - STAND[11]),
                1e-6f,
            )
            assertEquals(
                "第 $index 拍的膝应该左右反号",
                j[3] - STAND[3],
                -(j[13] - STAND[13]),
                1e-6f,
            )
        }
    }

    @Test
    fun `抬腿那几拍只有一条腿离开站立角`() {
        val lifted = keys[0].joints
        assertEquals("抬的是右腿：左腿的髋前后角不动", STAND[2], lifted[2], 1e-6f)
        assertTrue("右腿的髋前后角动了", lifted[12] != STAND[12])
        assertTrue("右膝也屈了", lifted[13] != STAND[13])
    }
}
