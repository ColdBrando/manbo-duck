package com.fanduck.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端线格式的断言。规格 §10 的表里没有它们，但 §9 第 8 步要求"抓一次请求，确认没有媒体
 * 字节、关节角和 conversation_id" —— 这里是能自动化的那一半。
 */
class CloudStreamTest {

    private val messages = listOf(
        mapOf("role" to "system", "content" to "你是鸭子"),
        mapOf("role" to "user", "content" to "语音：过来"),
        mapOf("role" to "user", "content" to "工具 look_now\nObservation: 摄像头正常"),
    )

    @Test
    fun `请求体带 model、messages、max_tokens、stream`() {
        val body = JSONObject(cloudRequestBody(messages, "deepseek-chat"))
        assertEquals("deepseek-chat", body.getString("model"))
        assertEquals(CLOUD_MAX_TOKENS, body.getInt("max_tokens"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(3, body.getJSONArray("messages").length())
    }

    @Test
    fun `请求体里没有 conversation_id，也没有多出来的字段`() {
        val body = JSONObject(cloudRequestBody(messages, "deepseek-chat"))
        assertFalse(body.has("conversation_id"))
        val keys = body.keys().asSequence().toSet()
        assertEquals(setOf("model", "messages", "max_tokens", "stream"), keys)
    }

    @Test
    fun `每条 messages 原样进去，Observation 那条也在`() {
        val body = JSONObject(cloudRequestBody(messages, "deepseek-chat"))
        val array = body.getJSONArray("messages")
        for (i in messages.indices) {
            assertEquals(messages[i]["role"], array.getJSONObject(i).getString("role"))
            assertEquals(messages[i]["content"], array.getJSONObject(i).getString("content"))
        }
        assertTrue(body.toString().contains("Observation:"))
    }

    @Test
    fun `增量文本取 delta 里的 content`() {
        val chunk = """{"choices":[{"index":0,"delta":{"content":"Final"}}]}"""
        assertEquals("Final", deltaText(chunk))
    }

    @Test
    fun `角色块 心跳 用量块 和坏 JSON 都不是正文`() {
        assertEquals("", deltaText("""{"choices":[{"delta":{"role":"assistant","content":""}}]}"""))
        assertEquals("", deltaText("""{"choices":[{"delta":{}}]}"""))
        assertEquals("", deltaText("""{"choices":[],"usage":{"total_tokens":12}}"""))
        assertEquals("", deltaText("""{"choices":[{"delta":{"reasoning_content":"嗯…"}}]}"""))
        assertEquals("", deltaText("{这不是 JSON"))
    }
}
