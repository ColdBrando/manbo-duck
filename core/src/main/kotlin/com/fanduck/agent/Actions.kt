package com.fanduck.agent

/** 规格 §3.5：白名单、裁剪、近距离拒绝。 */

fun clamp(value: Float, lo: Float, hi: Float): Float = minOf(hi, maxOf(lo, value))

/**
 * 速度的上下限（米/秒、弧度/秒）。**2026-09-28 从 ±0.2 提到 ±0.4** —— 这是真机的数：
 * 训练时 `lin_vel_x` 的范围就是 ±0.4（`microduck_velocity_env_cfg.py`），
 * 而真机训出来的步态**低于约 0.3 m/s 就选择站着**（实测 0.25 站、0.30 走）。
 * 上限还卡在 0.2 的话，鸭子永远走不出那个死区，接步态等于白接。
 */
const val MAX_VX = 0.4f
const val MAX_VY = 0.3f
const val MAX_WZ = 1.5f

/** 返回 null 表示名单外的动作，不发送。 */
fun clampAction(action: RobotAction): RobotAction? {
    return when (action.name) {
        "stop", "stand", "sit" -> action
        "velocity" -> action.copy(
            vx = clamp(action.vx, -MAX_VX, MAX_VX),
            vy = clamp(action.vy, -MAX_VY, MAX_VY),
            wz = clamp(action.wz, -MAX_WZ, MAX_WZ),
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
