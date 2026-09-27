package com.fanduck.agent

import org.json.JSONArray
import org.json.JSONObject

/** 规格 §2 的数据类型 + §3.4 的解析。 */

data class RobotAction(
    val name: String,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val wz: Float = 0f,
    val ms: Int = 0,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
)

data class FinalCommand(val say: String, val actions: List<RobotAction>)

sealed class ReactStep {
    data class Tool(val name: String, val inputJson: String) : ReactStep()
    data class Final(val command: FinalCommand) : ReactStep()
    data object Invalid : ReactStep()
}

/** 从标记后的第一个 { 取平衡对象；字符串和转义都算在内。 */
fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        if (inString) {
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') inString = false
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}

fun parseReact(raw: String): ReactStep {
    val finalAt = raw.indexOf("Final Answer:")
    if (finalAt >= 0) {
        val body = raw.substring(finalAt + "Final Answer:".length).trim()
        return ReactStep.Final(parseFinal(body))
    }
    val actionAt = raw.indexOf("Action:")
    if (actionAt < 0) return ReactStep.Invalid
    val actionName = raw.substring(actionAt + "Action:".length)
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.substringBefore(' ')
        ?: return ReactStep.Invalid
    val inputAt = raw.indexOf("Action Input:")
    val inputJson = if (inputAt < 0) "{}" else extractJsonObject(raw.substring(inputAt)) ?: "{}"
    if (actionName != "search_memory" && actionName != "look_now") return ReactStep.Invalid
    return ReactStep.Tool(actionName, inputJson)
}

fun parseFinal(body: String): FinalCommand {
    val json = extractJsonObject(body)
    if (json == null) return FinalCommand(say = body.trim(), actions = emptyList())
    val obj = JSONObject(json)
    val say = obj.optString("say")
    val arr = obj.optJSONArray("actions") ?: JSONArray()
    val actions = List(arr.length()) { i ->
        val item = arr.getJSONObject(i)
        RobotAction(
            name = item.optString("name"),
            vx = item.optDouble("vx", 0.0).toFloat(),
            vy = item.optDouble("vy", 0.0).toFloat(),
            wz = item.optDouble("wz", 0.0).toFloat(),
            ms = item.optInt("ms", 0),
            yaw = item.optDouble("yaw", 0.0).toFloat(),
            pitch = item.optDouble("pitch", 0.0).toFloat(),
        )
    }
    return FinalCommand(say, actions)
}
