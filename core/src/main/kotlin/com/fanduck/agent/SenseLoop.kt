package com.fanduck.agent

import java.util.Locale

/**
 * 规格 §4.1。循环本身（每 2 秒取帧）在 :app 里，这里只放可以单测的纯部分。
 *
 * 规格 §4.1 那句去重规则写得自相矛盾（"不同…也可以不写" 紧跟着 "只在文字变化…追加"），
 * 而且和 §1 的"每 2 秒写一条「看到」"、§11 的"只有 seen 在增加"冲突。这里按 §4.1
 * 的算法描述实现：文字变化就写，一条都没有时也写一条；文字没变就不写。见评审 P2。
 */
fun shouldWriteSeen(newText: String, lastSeen: Episode?): Boolean =
    lastSeen == null || lastSeen.text != newText

const val NO_DETECTOR_CAPTION = "摄像头正常，未识别物体"

fun composeSeenText(caption: String, distanceMeters: Float?): String =
    if (distanceMeters == null) caption else "$caption，最近距离 ${formatMeters(distanceMeters)} 米"

/**
 * 小数点是 . 不是 ,——nearestMeters 要能从写进去的文字里读回来，不能被系统语言带偏。
 */
fun formatMeters(value: Float): String =
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

/**
 * 规格 §4.1 的一拍：取一帧 → 写成一句说明 → 该写就写。返回写进去的那条，没写返回 null。
 *
 * `media` 是**只在真要写一条时**才调用的（§4.1 第 4 条：把这一帧覆盖写到 `seen/latest.jpg`，
 * 这个文件不上传）。做成 lambda 是为了不写的那一拍不去动磁盘 —— 每 2 秒写一次 jpg 没有意义，
 * 而文字变了才有新的一帧值得留。
 *
 * `look_now` 不走这里：模型点名要看，就无条件写一条（见 `AgentLoop`）。
 */
fun senseOnce(state: AgentState, media: () -> String = { "" }): Episode? {
    val (caption, distance) = state.sense.captionAndDistance()
    val text = composeSeenText(caption, distance)
    if (!shouldWriteSeen(text, state.episodes.lastOrNull { it.kind == Kind.SEEN })) return null
    return appendEpisode(state, Kind.SEEN, text, media())
}
