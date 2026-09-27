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

fun splitForCompact(transcript: Transcript): Pair<List<TranscriptMessage>, List<TranscriptMessage>> {
    val keep = transcript.messages.takeLast(6)
    val drop = transcript.messages.dropLast(6)
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
