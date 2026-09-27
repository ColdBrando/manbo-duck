package com.fanduck.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检测器那半边的断言：认出来的东西怎么变成一句话。跑模型的那半在 :app，这里不碰。
 */
class DetectorTest {

    private fun label(name: String, confidence: Float) = Label(name, confidence)

    @Test
    fun `什么都没认出来就是那句固定的话`() {
        assertEquals(NO_DETECTOR_CAPTION, captionFromLabels(emptyList()))
    }

    @Test
    fun `低于阈值的等于没认出来`() {
        // 51% 的猜测不该驱动鸭子走路，宁可说"未识别物体"
        assertEquals(NO_DETECTOR_CAPTION, captionFromLabels(listOf(label("人", 0.51f))))
        assertEquals(NO_DETECTOR_CAPTION, captionFromLabels(listOf(label("人", 0.59f))))
        assertEquals("看到 人", captionFromLabels(listOf(label("人", 0.6f))))
    }

    @Test
    fun `按分数排序 只取前三个`() {
        val labels = listOf(
            label("椅子", 0.62f),
            label("人", 0.91f),
            label("桌子", 0.88f),
            label("窗户", 0.79f),
        )
        assertEquals("看到 人、桌子、窗户", captionFromLabels(labels))
    }

    @Test
    fun `这句话里没有分数`() {
        val caption = captionFromLabels(listOf(label("人", 0.87f)))
        assertEquals("看到 人", caption)
        assertTrue("分数每拍都在抖，写进来去重就失效了", !caption.contains("%"))
    }

    @Test
    fun `同一幅画面分数抖动时 文字不变`() {
        // 这是踩过的：带了百分号之后，同一幅画面每 2 秒写一条新事件（77/73/71/73…）
        val a = captionFromLabels(listOf(label("人", 0.77f), label("椅子", 0.66f)))
        val b = captionFromLabels(listOf(label("人", 0.73f), label("椅子", 0.68f)))
        assertEquals(a, b)
        assertEquals("看到 人、椅子", a)
    }

    @Test
    fun `标签翻成中文 没收录的原样返回`() {
        assertEquals("人", labelName("Person"))
        assertEquals("狗", labelName("Dog"))
        assertEquals("椅子", labelName("Chair"))
        assertEquals("Pattern", labelName("Pattern"))   // 模拟器那幅测试图，没收录
        assertEquals("", labelName(""))
    }

    @Test
    fun `中文标签走进那句话`() {
        assertEquals("看到 狗、沙发", captionFromLabels(listOf(label("狗", 0.8f), label("沙发", 0.7f))))
    }
}
