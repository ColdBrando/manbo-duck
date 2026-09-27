package com.fanduck.agent

/** 规格 §3.5：白名单、裁剪、近距离拒绝。 */

fun clamp(value: Float, lo: Float, hi: Float): Float = minOf(hi, maxOf(lo, value))

/** 返回 null 表示名单外的动作，不发送。 */
fun clampAction(action: RobotAction): RobotAction? {
    return when (action.name) {
        "stop", "stand", "sit" -> action
        "velocity" -> action.copy(
            vx = clamp(action.vx, -0.2f, 0.2f),
            vy = clamp(action.vy, -0.1f, 0.1f),
            wz = clamp(action.wz, -1f, 1f),
            ms = clamp(action.ms.toFloat(), 100f, 2000f).toInt(),
        )
        "gaze" -> action.copy(
            yaw = clamp(action.yaw, -45f, 45f),
            pitch = clamp(action.pitch, -45f, 45f),
        )
        else -> null
    }
}

private val distanceRe = Regex("最近距离\\s*([0-9]+(?:\\.[0-9]+)?)\\s*米")

fun nearestMeters(seenText: String?): Float? {
    if (seenText == null) return null
    return distanceRe.find(seenText)?.groupValues?.get(1)?.toFloatOrNull()
}

/** 一条动作的处置结果。allowed 为 false 时 reason 要写进 did。 */
data class ActionDecision(
    val action: RobotAction,
    val allowed: Boolean,
    val reason: String = "",
)

/**
 * 下发前的过滤。顺序按规格：手柄占用、距离过近、未知动作。
 * 只有名字在名单里的动作才可能 allowed。
 */
fun resolveActions(
    actions: List<RobotAction>,
    latestSeenText: String?,
    padActive: Boolean,
): List<ActionDecision> {
    val nearest = nearestMeters(latestSeenText)
    return actions.map { raw ->
        val clamped = clampAction(raw)
        when {
            clamped == null -> ActionDecision(raw, false, "未知动作")
            padActive && clamped.name != "stop" -> ActionDecision(clamped, false, "手柄占用")
            clamped.name == "velocity" && clamped.vx > 0f && nearest != null && nearest < 0.3f ->
                ActionDecision(clamped, false, "距离过近")
            else -> ActionDecision(clamped, true)
        }
    }
}
