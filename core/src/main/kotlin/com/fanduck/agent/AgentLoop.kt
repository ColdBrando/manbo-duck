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
你是超能花钱鸭的交互大脑。用户刚说的话在「语音」里，用键盘打进来的是「打字」。
「记得」「听到」「打字」「看到」「做过」是鸭子端上的记忆，不是你亲眼看到的画面。
「记得」是长期记住的事，「听到」是最近说过的话。
没有记录的事情就说记录里没有，不要编造。

你可以：
Action: search_memory
Action Input: {"query":"检索句"}

Action: look_now
Action Input: {}

Action: remember
Action Input: {"text":"值得长期记住的一句话"}

什么时候用 remember：用户说了自己的名字、称呼、偏好、习惯，或者让你记住什么事的时候。
一次一句，写"用户喜欢喝美式"这样陈述句，不要写"用户说…"。
别的信息够用就不要用。
**remember 和 search_memory 一样是 Action，不是 actions 里的动作** —— 想记住就下一轮
先输出上面这三行，拿到 Observation 之后再给 Final Answer。

信息够了就结束，不要再输出 Action：
Thought: ...
Final Answer: {"say":"对用户说的话","actions":[{"name":"stop"}]}

actions 里**只能**放 stop、velocity、gaze、stand、sit 这五个动作 —— 放别的会被丢掉，
remember 放进来也一样（它是 Action，不是动作）。
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
    /** 长期记忆（`facts.jsonl`）。和 `episodes` 分开：那个按尾部截断读，这个全留着。 */
    val facts: MutableList<Episode> = mutableListOf(),
    var memoryState: MemoryState = MemoryState(),
    var busy: Boolean = false,
    /** 等着的还没轮到的句子。连来源一起存：排队时说话和打字可以混在一起。 */
    val queue: MutableList<Pair<String, Kind>> = mutableListOf(),
    val now: () -> String = ::isoNow,
    val sleep: (Int) -> Unit = { ms -> Thread.sleep(ms.toLong()) },
    val archive: (List<TranscriptMessage>) -> Unit = {},
    val onTranscript: (Transcript) -> Unit = {},
    /** `remember` 工具：合并去重 + 落盘，返回**合并之后的全部事实**。没接就是记不了。 */
    val remember: (String) -> List<Episode> = { emptyList() },
    /** 事实变了要落盘。 */
    val onFacts: (List<Episode>) -> Unit = {},
    /** 巩固进度变了要落盘。 */
    val onMemoryState: (MemoryState) -> Unit = {},
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
fun onHeard(raw: String, state: AgentState) = onUtterance(raw, Kind.HEARD, state)

/**
 * 键盘打进来的一句（debug 输入条）。和语音走同一条链，只是事件记成 `text`、
 * 意图里写「打字：」—— 模型不该以为这句话是说出来的。
 */
fun onTyped(raw: String, state: AgentState) = onUtterance(raw, Kind.TEXT, state)

/** 听到的和打进来的共用这一段：排队、busy、短句只记不推理，都一样。 */
fun onUtterance(raw: String, kind: Kind, state: AgentState) {
    val text = raw.trim()
    if (text.isEmpty()) return
    appendEpisode(state, kind, text)   // 短于 2 字也记
    if (text.length < 2) return
    if (state.busy) {
        state.queue += text to kind
        return
    }
    state.busy = true
    try {
        runReact(text, state, kind)
    } finally {
        state.busy = false
        if (state.queue.isNotEmpty()) {
            val (next, nextKind) = state.queue.removeAt(0)
            onUtterance(next, nextKind, state)
        }
    }
}

fun runReact(utterance: String, state: AgentState, source: Kind = Kind.HEARD) {
    // 当前这句已经写成一条事件（heard 或 text），所以对应那块里可以出现刚才的原话。
    val intent = buildIntent(utterance, state.episodes, state.now(), source, state.facts)
    state.transcript = state.transcript.copy(
        messages = state.transcript.messages + TranscriptMessage("user", intent),
    )
    try {
        reactLoop(state)
    } finally {
        // 成功、云端失败、「我没想好」都要把已产生的 assistant / tool 写盘，
        // 但不额外编造 Final Answer。
        state.onTranscript(state.transcript)
        // 这一轮结束了，顺便收拾一下记忆（攒够一批经历才会真的问云端一次）。
        consolidateIfNeeded(state)
    }
}

/**
 * 把经历收成事实（长期记忆）。攒够 `CONSOLIDATE_EVERY` 条新经历才问一次云端，所以绝大多数
 * 轮次这里是空转。
 *
 * **失败就当没发生**：这是收拾记忆，不是用户要的回答 —— 异常绝不能穿出去（评审 P0-2 那课），
 * 水位线也不动，下次再试。
 */
private fun consolidateIfNeeded(state: AgentState) {
    val batch = eventsToConsolidate(state.episodes, state.memoryState.consolidatedUpTo)
    if (batch.isEmpty()) return
    val raw = try {
        state.cloud.complete(
            listOf(mapOf("role" to "user", "content" to consolidationPrompt(batch))),
        )
    } catch (e: Exception) {
        return
    }
    val merged = upsertFacts(state.facts, parseFacts(raw, state.facts), state.now())
    state.facts.clear()
    state.facts.addAll(merged)
    state.onFacts(merged)
    // 水位线即使一条都没收到也要往前移：问过了，不值得记就是没值得记的。
    state.memoryState = MemoryState(consolidatedUpTo = batch.last().at)
    state.onMemoryState(state.memoryState)
}

private fun reactLoop(state: AgentState) {
    repeat(MAX_REACT_STEPS) { index ->
        val step = index + 1
        // 评审 P0-2：压缩自己也要访问云端，异常不能穿出 onHeard 的 finally ——
        // 那会让这一句语音白说（连"听不清"都来不及说）。降级：压缩失败就当这轮不压缩，照常推理。
        state.transcript = try {
            compactIfNeeded(state.transcript, state.cloud, state.archive)
        } catch (e: Exception) {
            state.transcript
        }
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
            // 长期记忆也一起搜：模型问"我叫什么"的时候，答案在 facts 里而不是事件流里
            val rows = selectEpisodes(
                state.episodes + state.facts,
                Kind.entries.toSet(),
                query,
                state.now(),
                SEARCH_MEMORY_K,
            )
            if (rows.isEmpty()) "Observation: 没有记录"
            else "Observation: " + rows.joinToString("\n") { lineOf(it) }
        }
    }

    /**
     * 主动记一条长期记忆。合并去重和落盘在 `state.remember` 里（那是 :app 接的
     * `FactsLog`），这里只管把结果同步回内存。
     */
    "remember" -> {
        val text = try {
            JSONObject(inputJson).optString("text")
        } catch (e: Exception) {
            ""
        }
        if (text.isBlank()) {
            "Observation: text 为空"
        } else {
            val merged = state.remember(text)
            if (merged.isEmpty()) {
                "Observation: 记不了（这台设备的长期记忆没接上）"
            } else {
                state.facts.clear()
                state.facts.addAll(merged)
                state.onFacts(merged)
                "Observation: 记下了"
            }
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
 *
 * `did` 记的是 `RobotPort` 回来的结果，不是"我们调过了"（评审 P1-4）：真机拒绝时，
 * 原因照样落进事件流，模型下一轮能看到。
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
        val ack = when (action.name) {
            "stop" -> state.robot.stop()
            "stand" -> state.robot.stand()
            "sit" -> state.robot.sit()
            "gaze" -> state.robot.gaze(action.yaw, action.pitch)
            "velocity" -> {
                val started = state.robot.velocity(action.vx, action.vy, action.wz)
                // 没跑起来就别等那 ms，也别补一个 stop —— 等的是"走完这一步"，不是"走了"。
                if (started.ok) {
                    state.sleep(action.ms)
                    state.robot.stop()
                }
                started
            }
            else -> Ack.OK
        }
        if (ack.ok) {
            appendEpisode(state, Kind.DID, "动作 ${action.name} 结果 ok")
        } else {
            appendEpisode(state, Kind.DID, "动作 ${action.name} 结果 fail 原因 ${ack.reason}")
        }
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
    val prompt = buildString {
        append("把下面这段旧轨迹收成不超过 $SUMMARY_MAX_CHARS 字的摘要，只输出摘要正文，不要标题。\n")
        if (transcript.summary.isNotBlank()) append("旧摘要：\n${transcript.summary}\n\n")
        append("要压缩的轨迹：\n")
        for (message in drop) append("${message.role}: ${message.content}\n")
    }
    // 先要到摘要再归档：反过来的话，云端失败时同一条消息既在归档里又在工作轨迹里，
    // 下一次压缩会把它们再归档一遍。
    val summary = cloud.complete(listOf(mapOf("role" to "user", "content" to prompt)))
        .trim()
        .take(SUMMARY_MAX_CHARS)
    archive(drop)
    return Transcript(summary, keep)
}
