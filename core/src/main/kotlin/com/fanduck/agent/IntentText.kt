package com.fanduck.agent

/** 规格 §3.3。第一次云端请求的 user 内容只用这个函数生成。 */

fun lineOf(episode: Episode): String = "${episode.id} ${episode.at} ${episode.text}"

/** 这句话是怎么进来的（意图第一行的来源前缀）。 */
fun Kind.sourceLabel(): String = when (this) {
    Kind.HEARD -> "语音"
    Kind.TEXT -> "打字"
    else -> "记录"
}

fun buildIntent(
    utterance: String,
    episodes: List<Episode>,
    nowIso: String,
    /** 这句是听到的还是打进来的。默认听到 —— 语音是主链路。 */
    source: Kind = Kind.HEARD,
): String {
    fun block(title: String, kind: Kind): String {
        val rows = selectEpisodes(episodes, setOf(kind), utterance, nowIso, k = 3)
        val body = if (rows.isEmpty()) "（没有记录）" else rows.joinToString("\n") { lineOf(it) }
        return "$title：\n$body"
    }
    val lines = mutableListOf("${source.sourceLabel()}：$utterance", block("听到", Kind.HEARD))
    // 「打字」这一块只在真的用过键盘输入时才出现：语音那条链路的意图保持 §3.3 原样四个块。
    if (source == Kind.TEXT || episodes.any { it.kind == Kind.TEXT }) {
        lines += block("打字", Kind.TEXT)
    }
    lines += block("看到", Kind.SEEN)
    lines += block("做过", Kind.DID)
    lines += "问题：$utterance"
    return lines.joinToString("\n\n")
}
