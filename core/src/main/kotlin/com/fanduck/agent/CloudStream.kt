package com.fanduck.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端的**线格式**（OpenAI 兼容，DeepSeek 走这套）。放 :core 是为了能断言：
 * 请求体里有什么、没有什么（§9 第 8 步那句"抓一次请求，确认没有媒体字节、关节角和
 * conversation_id"就落在这里），以及 SSE 分块里增量文本从哪个字段取。
 *
 * 规格 §4.3 说"请求体只有 messages 和 max_tokens: 1024"。**model 和 stream 是必须加的**：
 * 不写模型名服务端不知道用哪个模型，不开流式就没法实现 §4.2 的"8 秒没有下一个 token 抛异常"。
 * 那句的本意是"不要加 conversation_id、不要把 episodes.jsonl 全文塞进去"。
 */
const val CLOUD_MAX_TOKENS = 1024

fun cloudRequestBody(
    messages: List<Map<String, String>>,
    model: String,
    maxTokens: Int = CLOUD_MAX_TOKENS,
): String {
    val array = JSONArray()
    for (message in messages) {
        array.put(
            JSONObject()
                .put("role", message["role"].orEmpty())
                .put("content", message["content"].orEmpty()),
        )
    }
    return JSONObject()
        .put("model", model)
        .put("messages", array)
        .put("max_tokens", maxTokens)
        .put("stream", true)
        .toString()
}

/**
 * 一块 SSE 里的增量文本（OpenAI 兼容：`choices[0].delta.content`）。
 *
 * 角色块、心跳、用量块、坏 JSON 都返回空串 —— 它们都不是给用户看的正文。
 * `deepseek-reasoner` 那种把思维链放在 `delta.reasoning_content` 的块也返回空：
 * 规格要的是 Thought/Action/Final Answer 那套正文，思维链不进轨迹。
 */
fun deltaText(payload: String): String = try {
    JSONObject(payload)
        .optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("delta")
        ?.optString("content")
        .orEmpty()
} catch (e: Exception) {
    ""
}
