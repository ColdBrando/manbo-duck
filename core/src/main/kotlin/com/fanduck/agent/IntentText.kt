package com.fanduck.agent

/** 规格 §3.3。第一次云端请求的 user 内容只用这个函数生成。 */

fun lineOf(episode: Episode): String = "${episode.id} ${episode.at} ${episode.text}"

fun buildIntent(utterance: String, episodes: List<Episode>, nowIso: String): String {
    fun block(title: String, kind: Kind): String {
        val rows = selectEpisodes(episodes, setOf(kind), utterance, nowIso, k = 3)
        val body = if (rows.isEmpty()) "（没有记录）" else rows.joinToString("\n") { lineOf(it) }
        return "$title：\n$body"
    }
    return listOf(
        "语音：$utterance",
        block("听到", Kind.HEARD),
        block("看到", Kind.SEEN),
        block("做过", Kind.DID),
        "问题：$utterance",
    ).joinToString("\n\n")
}
