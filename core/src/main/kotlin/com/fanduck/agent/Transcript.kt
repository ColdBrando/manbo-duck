package com.fanduck.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 规格 §3.6：轨迹追加、打包、压缩切割。 */

data class TranscriptMessage(
    val role: String,        // user、assistant、tool
    val content: String,
    val name: String = "",   // 仅 tool：search_memory 或 look_now
)

data class Transcript(
    val summary: String = "",
    val messages: List<TranscriptMessage> = emptyList(),
)

/** 打包前如果全部消息字符数（summary 加上每条 content）超过这个数，先压缩。 */
const val TRANSCRIPT_CHAR_LIMIT = 12000
const val SUMMARY_MAX_CHARS = 800

fun transcriptChars(transcript: Transcript): Int =
    transcript.summary.length + transcript.messages.sumOf { it.content.length }

fun needsCompact(transcript: Transcript): Boolean = transcriptChars(transcript) > TRANSCRIPT_CHAR_LIMIT

/** 压缩后留在工作轨迹里的尾巴条数（规格 §3.6 的 keep 6）。 */
const val COMPACT_KEEP_TAIL = 6

/**
 * 规格 §3.6 的切割：尾巴 6 条留在工作轨迹，其余交给云端汇总。
 *
 * 评审 P0-3：一轮里 tool 往返会把消息推过 6 条，**本轮的 user 意图**（最后一条 user 消息）
 * 就被切进 drop、被摘要掉 —— 模型从第 7 条消息起看不到用户问的到底是什么。所以本轮意图
 * 单独钉住，keep 的上界变成 7 条；被跳过的那几条（同一轮里更早的往返）照样进摘要。
 *
 * 本轮意图本来就在尾巴里（常态）时不钉，行为与规格一致。
 */
fun splitForCompact(transcript: Transcript): Pair<List<TranscriptMessage>, List<TranscriptMessage>> {
    val messages = transcript.messages
    val tailStart = maxOf(0, messages.size - COMPACT_KEEP_TAIL)
    val anchor = messages.indexOfLast { it.role == "user" }
    if (anchor < 0 || anchor >= tailStart) {
        return messages.take(tailStart) to messages.drop(tailStart)
    }
    val keep = listOf(messages[anchor]) + messages.drop(tailStart)
    val drop = messages.filterIndexed { index, _ -> index < tailStart && index != anchor }
    return drop to keep
}

// ---------- 落盘：agent/transcript.json 和 agent/transcript-archive.jsonl ----------
// 规格第 1 节列了这两个文件但没给格式；这里用 org.json（Android 运行时自带）。

fun TranscriptMessage.toJson(): JSONObject = JSONObject()
    .put("role", role)
    .put("content", content)
    .apply { if (name.isNotEmpty()) put("name", name) }

fun Transcript.toJson(): String {
    val arr = JSONArray()
    for (message in messages) arr.put(message.toJson())
    return JSONObject().put("summary", summary).put("messages", arr).toString()
}

/** 一轮结束后写盘。写失败不该让 agent 那一轮失败。 */
fun writeTranscript(file: File, transcript: Transcript) {
    try {
        file.parentFile?.mkdirs()
        file.writeText(transcript.toJson())
    } catch (e: Exception) {
        // 忽略：丢的是工作轨迹的持久化，不是这一轮的推理
    }
}

fun readTranscript(file: File): Transcript {
    if (!file.isFile) return Transcript()
    return try {
        val obj = JSONObject(file.readText())
        val arr = obj.optJSONArray("messages") ?: JSONArray()
        val messages = List(arr.length()) { i ->
            val item = arr.getJSONObject(i)
            TranscriptMessage(item.optString("role"), item.optString("content"), item.optString("name"))
        }
        Transcript(obj.optString("summary"), messages)
    } catch (e: Exception) {
        Transcript()
    }
}

/** drop 追加到 transcript-archive.jsonl，一行一条。 */
fun appendArchive(file: File, drop: List<TranscriptMessage>) {
    if (drop.isEmpty()) return
    try {
        file.parentFile?.mkdirs()
        file.appendText(drop.joinToString("") { it.toJson().toString() + "\n" })
    } catch (e: Exception) {
        // 同上，归档失败不打断推理
    }
}
