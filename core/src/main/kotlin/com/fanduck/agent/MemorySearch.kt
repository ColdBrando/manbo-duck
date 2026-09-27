package com.fanduck.agent

import java.time.Duration
import java.time.OffsetDateTime

/**
 * 规格 §3.1 / §3.2。下面的函数是规格，单测按这些公式断言，不要改系数。
 */

private val tokenRe = Regex("[A-Za-z0-9_]+|[\\u4e00-\\u9fff]")

fun tokenize(text: String): List<String> = tokenRe.findAll(text).map { it.value }.toList()

fun keywordScore(query: List<String>, doc: List<String>): Float {
    if (query.isEmpty()) return 0f
    val docSet = doc.toSet()
    val hit = query.count { it in docSet }
    return hit.toFloat() / query.size
}

fun ageMinutes(nowIso: String, atIso: String): Float {
    val now = OffsetDateTime.parse(nowIso)
    val at = OffsetDateTime.parse(atIso)
    val minutes = Duration.between(at, now).toMillis() / 60000f
    return if (minutes < 0f) 0f else minutes
}

fun recency(ageMin: Float): Float = 1f / (1f + ageMin / 30f)

fun score(query: List<String>, episode: Episode, nowIso: String): Float {
    val kw = keywordScore(query, tokenize(episode.text))
    return 0.7f * kw + 0.3f * recency(ageMinutes(nowIso, episode.at))
}

/**
 * 拼意图和 search_memory 都调用这个函数。
 *
 * 注意：补满名额时用的是 `it.at` 字符串排序。ISO-8601 带时区的写法只有在所有事件的
 * 时区偏移一致时才等价于时间序（评审 P3）。手机上偏移固定，先用着。
 */
fun selectEpisodes(
    episodes: List<Episode>,
    kinds: Set<Kind>,
    queryText: String,
    nowIso: String,
    k: Int,
): List<Episode> {
    val pool = episodes.filter { it.kind in kinds }
    if (pool.isEmpty()) return emptyList()
    val query = tokenize(queryText)
    val matched = pool
        .map { it to keywordScore(query, tokenize(it.text)) }
        .filter { it.second > 0f }
        .sortedByDescending { score(query, it.first, nowIso) }
        .map { it.first }
    val picked = matched.take(k).toMutableList()
    if (picked.size < k) {
        val have = picked.map { it.id }.toSet()
        val recent = pool.sortedByDescending { it.at }
        for (episode in recent) {
            if (picked.size >= k) break
            if (episode.id !in have) picked += episode
        }
    }
    if (picked.isEmpty()) {
        picked += pool.maxByOrNull { it.at }!!
    }
    return picked
}
