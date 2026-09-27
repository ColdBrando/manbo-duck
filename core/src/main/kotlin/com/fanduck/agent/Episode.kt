package com.fanduck.agent

import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * 规格 §2 的三类，加上 `TEXT`。
 *
 * `TEXT` 是评审 P2 那条的后半：键盘打进来的话原来按 `heard` 记、意图里写「语音：xxx」，
 * 模型会以为这句话是说出来的。识别的和打的分开记，说话的那条链一个字都没动。
 */
enum class Kind { HEARD, TEXT, SEEN, DID }

data class Episode(
    val id: String,
    val at: String,          // ISO-8601，带时区，例如 2026-09-27T16:20:12+08:00
    val kind: Kind,
    val text: String,
    val media: String = "",  // 相对 agent/ 的路径。禁止放进云端请求
)

const val HEARD_MAX_CHARS = 200
const val SEEN_MAX_CHARS = 120
const val DID_MAX_CHARS = 200

/**
 * 读盘时只取尾部这么多条。
 *
 * 评审 P1-2：规格没写保留策略，而 selectEpisodes 每次都要对全部事件做正则分词+排序，
 * 2 秒一条 seen 跑一天就是几万条，热路径会越来越慢。这里用流式读+环形缓冲把上界钉住。
 */
const val DEFAULT_EPISODE_READ_LIMIT = 2000

val ISO_OFFSET: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

fun isoNow(): String = OffsetDateTime.now().format(ISO_OFFSET)

fun Kind.wireName(): String = name.lowercase()

fun kindOrNull(raw: String): Kind? = when (raw.trim().lowercase()) {
    "heard" -> Kind.HEARD
    "text" -> Kind.TEXT
    "seen" -> Kind.SEEN
    "did" -> Kind.DID
    else -> null
}

fun maxCharsOf(kind: Kind): Int = when (kind) {
    Kind.HEARD -> HEARD_MAX_CHARS
    Kind.TEXT -> HEARD_MAX_CHARS   // 一句话的长度，和听到的同一档
    Kind.SEEN -> SEEN_MAX_CHARS
    Kind.DID -> DID_MAX_CHARS
}

/** 按字符截断，不是按字节。 */
fun truncateFor(kind: Kind, text: String): String {
    val max = maxCharsOf(kind)
    return if (text.length <= max) text else text.substring(0, max)
}

fun Episode.toJson(): String {
    val o = JSONObject()
        .put("id", id)
        .put("at", at)
        .put("kind", kind.wireName())
        .put("text", text)
    if (media.isNotEmpty()) o.put("media", media)
    return o.toString()
}

/** 读到坏行返回 null，由调用方跳过。 */
fun episodeFromJson(line: String): Episode? = try {
    val o = JSONObject(line)
    val kind = kindOrNull(o.optString("kind"))
    if (kind == null) {
        null
    } else {
        Episode(
            id = o.optString("id"),
            at = o.optString("at"),
            kind = kind,
            text = o.optString("text"),
            media = o.optString("media"),
        )
    }
} catch (e: Exception) {
    null
}

interface EpisodeSink {
    fun append(kind: Kind, text: String, media: String = ""): Episode
}

/** 单测用的内存实现：id 稳定可断言，不碰磁盘。 */
class MemoryEpisodeSink(private val now: () -> String = ::isoNow) : EpisodeSink {

    private val seq = AtomicInteger(0)

    override fun append(kind: Kind, text: String, media: String): Episode = Episode(
        id = "${kind.wireName()}-${seq.incrementAndGet()}",
        at = now(),
        kind = kind,
        text = truncateFor(kind, text),
        media = media,
    )
}

/** agent/episodes.jsonl 的读写。一行一个对象，kind 用小写。 */
class EpisodeLog(
    private val file: File,
    private val now: () -> String = ::isoNow,
) : EpisodeSink {

    private val seq = AtomicInteger(0)

    override fun append(kind: Kind, text: String, media: String): Episode {
        val episode = Episode(
            id = "${kind.wireName()}-${System.currentTimeMillis()}-${seq.incrementAndGet()}",
            at = now(),
            kind = kind,
            text = truncateFor(kind, text),
            media = media,
        )
        file.parentFile?.mkdirs()
        file.appendText(episode.toJson() + "\n")
        return episode
    }

    fun read(limit: Int = DEFAULT_EPISODE_READ_LIMIT): List<Episode> = readEpisodes(file, limit)
}

fun readEpisodes(file: File, limit: Int = DEFAULT_EPISODE_READ_LIMIT): List<Episode> {
    if (!file.isFile) return emptyList()
    val tail = ArrayDeque<Episode>()
    file.bufferedReader().useLines { lines ->
        for (line in lines) {
            if (line.isBlank()) continue
            val episode = episodeFromJson(line) ?: continue
            tail.addLast(episode)
            while (tail.size > limit) tail.removeFirst()
        }
    }
    return tail.toList()
}
