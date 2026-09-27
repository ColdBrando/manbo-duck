package com.fanduck.agent

import org.json.JSONObject

const val MAX_REACT_STEPS = 8
const val SEARCH_MEMORY_K = 5
const val INTENT_K = 3

const val STEP_LIMIT_HINT = "已达步数上限，请只输出 Thought 和 Final Answer。"
const val FORMAT_HINT = "Observation: 请只输出 Thought/Action/Action Input，或 Thought/Final Answer。"
const val CLOUD_FAIL_SAY = "我现在听不清网上的回答"
const val GIVE_UP_SAY = "我没想好"

/** 规格 §7。不要改动作名。 */
val SYSTEM_PROMPT = """
你是超能花钱鸭的交互大脑。用户刚说的话在「语音」里。
「听到」「看到」「做过」是鸭子端上的记忆，不是你亲眼看到的画面。
没有记录的事情就说记录里没有，不要编造。

你可以：
Action: search_memory
Action Input: {"query":"检索句"}

Action: look_now
Action Input: {}

信息够了就结束，不要再输出 Action：
Thought: ...
Final Answer: {"say":"对用户说的话","actions":[{"name":"stop"}]}

actions 里的 name 只能是 stop、velocity、gaze、stand、sit。
让鸭子动之前，先看「看到」里的距离。最近距离小于 0.3 米时不要给前进速度。
用中文思考。say 用短句。
""".trim()

/**
 * 规格 §4.2。线程安全由调用方保证：只有一条 agent 线程执行 runReact，
 * 语音回调和摄像头回调把结果丢进这条线程，不要在回调里访问云端。
 */
class AgentState(
    val cloud: CloudClient,
    val robot: RobotPort,
    val sense: SensePort,
    val log: EpisodeSink = MemoryEpisodeSink(),
    var transcript: Transcript = Transcript(),
    val episodes: MutableList<Episode> = mutableListOf(),
    var busy: Boolean = false,
    val queue: MutableList<String> = mutableListOf(),
    val now: () -> String = ::isoNow,
    val sleep: (Int) -> Unit = { ms -> Thread.sleep(ms.toLong()) },
    val archive: (List<TranscriptMessage>) -> Unit = {},
    val onTranscript: (Transcript) -> Unit = {},
)

fun appendEpisode(state: AgentState, kind: Kind, text: String, media: String = ""): Episode {
    val episode = state.log.append(kind, text, media)
    state.episodes += episode
    return episode
}

/**
 * 听到一句之后。识别结束才调用这里；音频本身不上传。
 * 短于 2 字只记不推理。busy 时排队，当前一轮结束后按队列继续。
 */
fun onHeard(raw: String, state: AgentState) {
    val text = raw.trim()
    if (text.isEmpty()) return
    appendEpisode(state, Kind.HEARD, text)   // 短于 2 字也记
    if (text.length < 2) return
    if (state.busy) {
        state.queue += text
        return
    }
    state.busy = true
    try {
        runReact(text, state)
    } finally {
        state.busy = false
        if (state.queue.isNotEmpty()) {
            val next = state.queue.removeAt(0)
            onHeard(next, state)
        }
    }
}

fun runReact(utterance: String, state: AgentState) {
    // 当前这句已经写成一条 heard，所以「听到」里可以出现刚才的原话。
    val intent = buildIntent(utterance, state.episodes, state.now())
    state.transcript = state.transcript.copy(
        messages = state.transcript.messages + TranscriptMessage("user", intent),
    )
    try {
        reactLoop(state)
    } finally {
        // 成功、云端失败、「我没想好」都要把已产生的 assistant / tool 写盘，
        // 但不额外编造 Final Answer。
        state.onTranscript(state.transcript)
    }
}

private fun reactLoop(state: AgentState) {
    repeat(MAX_REACT_STEPS) { index ->
        val step = index + 1
        // 评审 P0-2：这个调用本身要访问云端，异常不在下面的 try 里，会穿出 onHeard 的 finally。
        // 规格就是这么写的，先照做；要不要给它包一层降级，等你定。
        state.transcript = compactIfNeeded(state.transcript, state.cloud, state.archive)
        val messages = pack(state.transcript)
        val sent = if (step == MAX_REACT_STEPS) {
            messages + mapOf("role" to "user", "content" to STEP_LIMIT_HINT)
        } else {
            messages
        }
        val raw = try {
            state.cloud.complete(sent)
        } catch (e: Exception) {
            state.robot.stop()
            state.robot.say(CLOUD_FAIL_SAY)
            return
        }
        state.transcript = state.transcript.copy(
            messages = state.transcript.messages + TranscriptMessage("assistant", raw),
        )
        when (val parsed = parseReact(raw)) {
            is ReactStep.Final -> {
                dispatch(parsed.command, state)
                return
            }
            is ReactStep.Tool -> {
                val obs = executeTool(parsed.name, parsed.inputJson, state)
                state.transcript = state.transcript.copy(
                    messages = state.transcript.messages + TranscriptMessage("tool", obs, parsed.name),
                )
            }
            ReactStep.Invalid -> {
                state.transcript = state.transcript.copy(
                    messages = state.transcript.messages + TranscriptMessage("tool", FORMAT_HINT, "format"),
                )
            }
        }
    }
    state.robot.stop()
    state.robot.say(GIVE_UP_SAY)
}

private fun executeTool(name: String, inputJson: String, state: AgentState): String = when (name) {
    "search_memory" -> {
        val query = try {
            JSONObject(inputJson).optString("query")
        } catch (e: Exception) {
            ""
        }
        if (query.isBlank()) {
            "Observation: query 为空"
        } else {
            val rows = selectEpisodes(
                state.episodes,
                Kind.entries.toSet(),
                query,
                state.now(),
                SEARCH_MEMORY_K,
            )
            if (rows.isEmpty()) "Observation: 没有记录"
            else "Observation: " + rows.joinToString("\n") { lineOf(it) }
        }
    }
    "look_now" -> {
        val (caption, distance) = state.sense.captionAndDistance()
        val episode = appendEpisode(state, Kind.SEEN, composeSeenText(caption, distance))
        "Observation: ${lineOf(episode)}"
    }
    else -> "Observation: 未知工具"
}

/**
 * 逐条处置动作。say 先播，且不受任何过滤影响；每条实际发出或被拒绝的动作都追加 did。
 * velocity 发出去之后在 agent 线程上等待 ms 毫秒再 stop，等待期间不发第二个云端请求。
 */
private fun dispatch(command: FinalCommand, state: AgentState) {
    if (command.say.isNotEmpty()) state.robot.say(command.say)
    val latestSeenText = state.episodes.lastOrNull { it.kind == Kind.SEEN }?.text
    val decisions = resolveActions(command.actions, latestSeenText, state.robot.padActive())
    for (decision in decisions) {
        if (!decision.allowed) {
            appendEpisode(state, Kind.DID, "动作 ${decision.action.name} 结果 fail 原因 ${decision.reason}")
            continue
        }
        val action = decision.action
        when (action.name) {
            "stop" -> state.robot.stop()
            "stand" -> state.robot.stand()
            "sit" -> state.robot.sit()
            "gaze" -> state.robot.gaze(action.yaw, action.pitch)
            "velocity" -> {
                state.robot.velocity(action.vx, action.vy, action.wz)
                state.sleep(action.ms)
                state.robot.stop()
            }
        }
        appendEpisode(state, Kind.DID, "动作 ${action.name} 结果 ok")
    }
}

/** 规格 §4.3。请求体只有 messages 和 max_tokens，工具结果改写成 user 角色。 */
fun pack(transcript: Transcript): List<Map<String, String>> {
    val out = mutableListOf<Map<String, String>>()
    out += mapOf("role" to "system", "content" to SYSTEM_PROMPT)
    if (transcript.summary.isNotBlank()) {
        out += mapOf("role" to "user", "content" to "更早的轨迹摘要：\n${transcript.summary}")
        out += mapOf("role" to "assistant", "content" to "已记下。")
    }
    for (message in transcript.messages) {
        if (message.role == "tool") {
            val head = if (message.name.isBlank()) "" else "工具 ${message.name}\n"
            out += mapOf("role" to "user", "content" to head + message.content)
        } else {
            out += mapOf(
                "role" to ("assistant".takeIf { message.role == "assistant" } ?: "user"),
                "content" to message.content,
            )
        }
    }
    return out
}

/**
 * 规格 §3.6。超过 12000 字才压缩；drop 追加进归档，keep 留在工作轨迹，summary 由云端重写。
 * drop 为空则不调用云端。压缩请求不计入 8 步。
 */
fun compactIfNeeded(
    transcript: Transcript,
    cloud: CloudClient,
    archive: (List<TranscriptMessage>) -> Unit = {},
): Transcript {
    if (!needsCompact(transcript)) return transcript
    val (drop, keep) = splitForCompact(transcript)
    if (drop.isEmpty()) return transcript
    archive(drop)
    val prompt = buildString {
        append("把下面这段旧轨迹收成不超过 $SUMMARY_MAX_CHARS 字的摘要，只输出摘要正文，不要标题。\n")
        if (transcript.summary.isNotBlank()) append("旧摘要：\n${transcript.summary}\n\n")
        append("要压缩的轨迹：\n")
        for (message in drop) append("${message.role}: ${message.content}\n")
    }
    val summary = cloud.complete(listOf(mapOf("role" to "user", "content" to prompt)))
        .trim()
        .take(SUMMARY_MAX_CHARS)
    return Transcript(summary, keep)
}
