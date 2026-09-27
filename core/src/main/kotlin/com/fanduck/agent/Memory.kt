package com.fanduck.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 长期记忆：把"经历"（`episodes.jsonl`，每 2 秒一条「看到」）收成"事实"（`facts.jsonl`，
 * 几十条"用户叫 emin"这种）。
 *
 * 为什么**单独一个文件**而不是往 `episodes.jsonl` 里塞一类新事件：事件密度太高了
 * （每 2 秒一条 seen，一小时就 1800 条），而读盘只取尾部 2000 条 —— 事实混在里面会被
 * 挤出窗口，跑一下午鸭子就把用户的名字忘了。事实量小、要全量留着，所以分开存。
 *
 * 事实本身仍然用 `Episode` 表示（kind = `Kind.FACT`），这样检索、渲染、jsonl 读写
 * 全部复用现成的 —— `selectEpisodes(facts, setOf(Kind.FACT), query, now, k)` 直接能用。
 *
 * 两条写入路径：
 * 1. **模型主动记**：`remember` 工具（"记住我叫 emin"）；
 * 2. **自动巩固**：攒够 `CONSOLIDATE_EVERY` 条新经历，问一次云端"这些里哪些值得长期记住"。
 */

/** 事实的量：超过就丢最旧的。几十条够用了，多了检索会稀释。 */
const val MAX_FACTS = 60

/** 一条事实最长多少字（进「记得」那一块，别让一条长句挤掉别的）。 */
const val FACT_MAX_CHARS = 120

/** 攒够这么多条新经历才值得问一次云端做巩固。 */
const val CONSOLIDATE_EVERY = 40

/** 一次巩固最多收几条（问模型要的上限，也是解析时的兜底）。 */
const val CONSOLIDATE_MAX_FACTS = 5

/** 两条事实的字面重合超过这个比例就算重复，不再记。 */
const val FACT_DUPLICATE_OVERLAP = 0.8f

/** 事实落盘的文件名（在 `agent/` 下）。 */
const val FACTS_FILE = "facts.jsonl"

/** 巩固进度落盘的文件名 —— 记着"到哪条经历为止已经巩固过了"。 */
const val MEMORY_STATE_FILE = "memory-state.json"

// ---------- 什么时候值得巩固 ----------

/**
 * 该巩固了吗？返回要交给云端的那批经历（按时间升序），不值得就返回空。
 *
 * 只取**上次巩固之后**的 `heard` / `text` / `did`：
 * - `seen` 绝大多数是"摄像头正常"这种噪音，而且量大（每 2 秒一条），丢进去只会淹没有用的；
 *   想找"看到过什么"还有 `search_memory`。
 * - `fact` 不参与（那是巩固的产物，不是原料）。
 */
fun eventsToConsolidate(
    episodes: List<Episode>,
    consolidatedUpTo: String,
    minCount: Int = CONSOLIDATE_EVERY,
): List<Episode> {
    val fresh = episodes
        .filter { it.kind == Kind.HEARD || it.kind == Kind.TEXT || it.kind == Kind.DID }
        .filter { it.at > consolidatedUpTo }      // ISO-8601 同时区下可直接比字符串
        .sortedBy { it.at }
    return if (fresh.size >= minCount) fresh else emptyList()
}

/** 问云端要事实的那段话。纯的，能单测。 */
fun consolidationPrompt(events: List<Episode>): String = buildString {
    append("下面是这台设备最近的经历（时间 事件）。\n")
    append("挑出**值得长期记住**的，收成不超过 $CONSOLIDATE_MAX_FACTS 条短句。\n")
    append("只记这两类：关于用户的（名字、称呼、偏好、习惯、身份），以及这个环境里长期成立的事。\n")
    append("不要记：一次性的问候、道谢、'摄像头正常'这类状态、以及任何推测。\n")
    append("每条一句话、中文、不超过 $FACT_MAX_CHARS 字。没有值得记的就输出 []。\n")
    append("只输出 JSON 数组，每项一个字符串，不要解释、不要代码块。\n\n")
    append("经历：\n")
    for (event in events) append("${event.at} ${event.text}\n")
}

/**
 * 解析模型给的事实。模型不会每次都规规矩矩只吐一个数组，所以：
 * 掐头去尾找第一个 `[` 到最后一个 `]`；元素既收字符串，也收 `{"text": "..."}` 这种对象；
 * 空串、超长的、重复的（跟 `existing` 重合超 `FACT_DUPLICATE_OVERLAP`）都丢掉。
 */
fun parseFacts(raw: String, existing: List<Episode> = emptyList()): List<String> {
    val start = raw.indexOf('[')
    val end = raw.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    val array = try {
        JSONArray(raw.substring(start, end + 1))
    } catch (e: Exception) {
        return emptyList()
    }
    val out = mutableListOf<String>()
    for (i in 0 until array.length()) {
        if (out.size >= CONSOLIDATE_MAX_FACTS) break
        val item = array.opt(i)
        val text = when (item) {
            is String -> item
            is JSONObject -> item.optString("text")
            else -> ""
        }.trim().trim('。', '"', '\'', '-', ' ').take(FACT_MAX_CHARS)
        if (text.isEmpty()) continue
        if (isDuplicateFact(text, existing)) continue
        if (out.any { isDuplicateFact(text, listOf(factLike(it))) }) continue
        out += text
    }
    return out
}

/** 跟已有的事实（以及刚收的）比对，字面重合太高就不重复记。 */
fun isDuplicateFact(candidate: String, existing: List<Episode>): Boolean {
    val tokens = tokenize(candidate)
    if (tokens.isEmpty()) return true
    return existing.any { keywordScore(tokens, tokenize(it.text)) >= FACT_DUPLICATE_OVERLAP }
}

/** 造一个只用来比对的假事实（`isDuplicateFact` 只要 text）。 */
private fun factLike(text: String): Episode = Episode("", "", Kind.FACT, text)

/**
 * 装不下了丢最旧的。
 *
 * 注意"旧"是靠 `at` 算的，而**重复提到的事会被 [upsertFacts] 刷新时间** —— 所以
 * "我叫 emin"这种一直在用的不会被丢掉，被丢掉的是那些提过一次就再没出现过的。
 */
fun pruneFacts(facts: List<Episode>, max: Int = MAX_FACTS): List<Episode> =
    if (facts.size <= max) facts else facts.sortedByDescending { it.at }.take(max)

/**
 * 把新收的事实并进已有的：
 *
 * - 已经有的（字面重合超 `FACT_DUPLICATE_OVERLAP`）**刷新它的时间**，不重复记 ——
 *   反复提到的说明它还活着，久不提到的会自己变旧、被 [pruneFacts] 丢掉；
 * - 新的追加在后面；
 * - 超过 `max` 丢最旧的。
 *
 * 返回新的整个列表（量小，整体重写不心疼）。
 */
fun upsertFacts(
    existing: List<Episode>,
    fresh: List<String>,
    nowIso: String,
    max: Int = MAX_FACTS,
): List<Episode> {
    val out = existing.toMutableList()
    for (text in fresh) {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) continue
        val hit = out.indexOfFirst { keywordScore(tokens, tokenize(it.text)) >= FACT_DUPLICATE_OVERLAP }
        if (hit >= 0) {
            out[hit] = out[hit].copy(at = nowIso)   // 还活着：刷新时间
        } else {
            out += Episode(
                id = "fact-${nowIso}-${out.size}",
                at = nowIso,
                kind = Kind.FACT,
                text = truncateFor(Kind.FACT, text),
            )
        }
    }
    return pruneFacts(out, max)
}

// ---------- 落盘 ----------

/**
 * 事实的读写。格式和 `episodes.jsonl` 一样（一行一个 Episode），但**读全量、不按尾部截断**
 * —— 事实本来就少，全留着才有意义。
 *
 * 写入是"读一遍 → 合并 → 整体重写"：事实只有几十条，而 `remember` 又不是热路径
 * （一拍 2 秒的是「看到」，不写这儿）。
 */
class FactsLog(private val file: File) {

    fun read(limit: Int = MAX_FACTS * 4): List<Episode> = readEpisodes(file, limit)

    /**
     * 记一条事实。已经有了（字面重合太高）就**刷新它的时间**，不重复记。
     * 返回**合并之后的全部事实**（调用方拿它直接替换内存里那份）；传进来是空的就原样返回。
     */
    fun remember(text: String, at: String = isoNow()): List<Episode> {
        val trimmed = text.trim().take(FACT_MAX_CHARS)
        val existing = read()
        if (trimmed.isEmpty()) return existing
        val merged = upsertFacts(existing, listOf(trimmed), at)
        rewrite(merged)
        return merged
    }

    /** 整体重写（巩固之后、或裁掉旧的之后）。量小，不心疼。 */
    fun rewrite(facts: List<Episode>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(facts.joinToString("") { it.toJson() + "\n" })
        } catch (e: Exception) {
            // 写失败不该让这一轮推理失败
        }
    }
}

/** 巩固进度：到哪条经历为止已经收过了。丢了就重收一遍，不会出错，只是多花一次云调用。 */
data class MemoryState(val consolidatedUpTo: String = "") {

    fun toJson(): String = JSONObject().put("consolidatedUpTo", consolidatedUpTo).toString()
}

fun readMemoryState(file: File): MemoryState {
    if (!file.isFile) return MemoryState()
    return try {
        MemoryState(JSONObject(file.readText()).optString("consolidatedUpTo"))
    } catch (e: Exception) {
        MemoryState()
    }
}

fun writeMemoryState(file: File, state: MemoryState) {
    try {
        file.parentFile?.mkdirs()
        file.writeText(state.toJson())
    } catch (e: Exception) {
        // 写失败不该让这一轮推理失败：下次重收一遍而已
    }
}
