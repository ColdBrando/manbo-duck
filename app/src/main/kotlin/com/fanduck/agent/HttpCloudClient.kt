package com.fanduck.agent

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 规格 §4.2 / §4.3 / §5：真实的 HTTPS。请求体由 :core 的 `cloudRequestBody` 拼
 * （model + messages + max_tokens + stream），解析由 `deltaText` 负责 —— 那两块是纯的，
 * 在 `CloudStreamTest` 里有断言；这里只管连接、流式读、超时和重试。
 *
 * 流式不是花活：§4.2 要求"8 秒没有下一个 token 抛异常"。做法是把 socket 的 readTimeout
 * 设成 8 秒 —— 每读到一块就重置，读超时就是"卡住了"，直接抛给调用方（那边会 `stop()`
 * 并说"我现在听不清网上的回答"）。
 *
 * 只发文字：媒体字节、关节角、conversation_id 都不在请求里（§8）。密钥来自
 * `local.properties`（不进版本库），但**会明文编进 BuildConfig**。
 */
class HttpCloudClient(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String = BuildConfig.DUCK_MODEL,
) : CloudClient {

    override fun complete(messages: List<Map<String, String>>): String {
        if (apiKey.isBlank()) error("local.properties 里没有 duck.apiKey")
        val body = cloudRequestBody(messages, model)
        // 只报条数和字节数：请求体里是系统提示词 + 记忆块 + 轨迹，全文几 KB，打出来会淹掉 logcat。
        // 想看全文去日志页的「上下文」页签（那里是同一份东西）。
        Log.i(TAG, "发出 ${messages.size} 条消息，${body.length} 字节")
        val text = try {
            // §4.2「连接失败重试 1 次」只覆盖**建立连接**这一段：被拒、域名解析不了、连接超时。
            // 读数据阶段的超时是"8 秒没有下一个 token"，那是这一轮的结论，不重试。
            val connection = try {
                connect(body)
            } catch (e: IOException) {
                Log.i(TAG, "连接失败（${e.javaClass.simpleName}），按 §4.2 重试一次：${e.message}")
                connect(body)
            }
            read(connection)
        } catch (e: Exception) {
            // 这一行是手测时的眼睛：超时（8 秒没 token）、401、空回复都从这儿看出来
            Log.i(TAG, "这一轮云端失败：${e.javaClass.simpleName}：${e.message}")
            throw e
        }
        // 200 但一个字都没有：多半是流式被关掉或字段对不上，宁可当成出错
        if (text.isBlank()) error("云端返回了空内容")
        // 模型的原话（Thought / Final Answer）整段打出来 —— "鸭子没听清"的时候看这里
        Log.i(TAG, "收到 ${text.length} 字：\n$text")
        return text
    }

    private fun connect(body: String): HttpURLConnection {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = TOKEN_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            error("云端返回 $code：${detail.take(300)}")
        }
        return connection
    }

    private fun read(connection: HttpURLConnection): String {
        try {
            val out = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith(DATA)) continue
                    val payload = line.removePrefix(DATA).trim()
                    if (payload == DONE) break
                    out.append(deltaText(payload))
                }
            }
            return out.toString()
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "duck-cloud"

        /** SSE：一行一块，`data: {...}`，结束是 `data: [DONE]`。 */
        const val DATA = "data:"
        const val DONE = "[DONE]"

        const val CONNECT_TIMEOUT_MS = 10_000

        /** §4.2：8 秒没有下一个 token 就抛。每读到一块就重置。 */
        const val TOKEN_TIMEOUT_MS = 8_000
    }
}
