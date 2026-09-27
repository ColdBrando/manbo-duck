package com.fanduck.agent

/**
 * 语音识别（STT）的端口。规格 §5 说"语音用本机识别"，但**"本机"到底是谁在识别，
 * 由设备决定**，这里把它做成可替换的一块：
 *
 * ```
 * VoiceInput（本文件上面的节拍/静音窗口/排队，一个字都不用改）
 *      │  挑一个能用的
 *      ▼
 *   SttSource ──┬── AndroidSttSource（端上 / 系统默认，见 :app）
 *               ├── 讯飞 SDK（没做）
 *               ├── 小米 / 华为 / 讯飞的系统识别服务（没做）
 *               └── 自己打包的离线引擎 sherpa-onnx / Vosk（没做）
 * ```
 *
 * 换国产系统的识别服务 = **实现这一个接口**：接上它的 SDK，把它的回调翻成
 * `SttEvent.Heard` / `SttEvent.Failed`，其余（排队、busy、静音窗口、连续听的退避、
 * 前台服务的生命周期）全都不用动。
 *
 * 为什么这件事值得抽象：Android 的 `SpeechRecognizer` **自己不识别**，它只是绑到设备上
 * 另一个 app 的 `RecognitionService`。AOSP 不带识别器 —— 谁提供由设备决定。GMS 机器上
 * 默认那个是 Google 的，识别在它的服务器上做（音频要流上去）；国行 ROM 上一般是厂商的，
 * 也是传到厂商的服务器。所以"音频不上传"这句话，取决于绑到了谁。
 */
sealed interface SttEvent {
    /** 听到了一句。识别结束才来（规格 §5：中间结果不要）。 */
    data class Heard(val text: String) : SttEvent

    /** 这一轮没听清或者出错了。code 沿用系统的错误码，message 给人看。 */
    data class Failed(val code: Int, val message: String) : SttEvent
}

/**
 * 一个识别来源。
 *
 * 生命周期：`start()` 开一轮 → 回调里来一条 `Heard` 或 `Failed` → 想要下一轮就再 `start()`。
 * `stop()` 是"把这半句交出来"（按住说话松开时用），`cancel()` 是"不要了"。
 */
interface SttSource {

    /** 稳定标识，进日志和设置，别改（"on-device" / "system" / "iflytek"…）。 */
    val id: String

    /** 给人看的名字（"端上识别" / "系统识别" / "讯飞"…）。 */
    val label: String

    /**
     * 音频出不出这台手机。端上识别是 true；系统默认和厂商云识别是 false
     * —— 这句会写进日志和 README，别为了好听写成 true。
     */
    val onDevice: Boolean

    /** 这台设备上现在能不能用。不能用的不进候选。 */
    val available: Boolean

    var onEvent: ((SttEvent) -> Unit)?

    fun start()

    /** 收尾：这一句识别完会回调 `Heard`。 */
    fun stop()

    /** 不要了，别回调。 */
    fun cancel()

    /** 彻底释放（退出时）。 */
    fun destroy()
}

/**
 * 挑一个识别源。规则从硬到软：
 *
 * 1. 不在 `dead` 里（判过死刑的源不再碰 —— 见 `nextStt`）；
 * 2. `prefer` 指定的那个（用户/规格点名要的），但它得可用；
 * 3. **端上识别**（音频不出手机）优先于云的 —— 这一条是隐私，不是性能；
 * 4. 剩下的按传进来的顺序（调用方按"最可能好用"排）。
 *
 * 都没有就返回 null：语音输入不开，别装作能听。
 */
fun chooseStt(
    sources: List<SttSource>,
    prefer: String? = null,
    dead: Set<String> = emptySet(),
): SttSource? {
    val usable = usableStt(sources, dead)
    prefer?.let { id -> usable.firstOrNull { it.id == id }?.let { return it } }
    return usable.firstOrNull { it.onDevice } ?: usable.firstOrNull()
}

/**
 * 换下一个候选。当前这个被判了死刑（见 `fatalForSource`）或者根本起不来时用。
 * 返回 null 表示没得换了 —— 那就把语音输入关掉，别空转。
 *
 * `dead` 是**必须的**，不是可选优化：两个源都"声称可用"但都不支持中文时（模拟器上就是
 * on-device 报 12、system 报 12），不拉黑就会在两者之间来回换，换到天荒地老。
 */
fun nextStt(
    candidates: List<SttSource>,
    current: SttSource?,
    dead: Set<String> = emptySet(),
): SttSource? {
    val usable = usableStt(candidates, dead).filter { it !== current }
    return usable.firstOrNull { it.onDevice } ?: usable.firstOrNull()
}

private fun usableStt(sources: List<SttSource>, dead: Set<String>): List<SttSource> =
    sources.filter { it.available && it.id !in dead }

/**
 * 这个错误说明"**这个源根本用不了**"（语言不支持、语言数据没下），
 * 该换下一个源，而不是在原地重挂。
 *
 * 其余错误（没听清、超时、网络抽风）都是"这一轮不行"，重挂就好。
 * 错误码是 Android `SpeechRecognizer` 的那一套：12 = 语言不支持，13 = 语言数据没下。
 */
fun fatalForSource(code: Int): Boolean = code == 12 || code == 13

/**
 * 错误码翻成人话。码是 Android `SpeechRecognizer` 的那一套（各家 SDK 实现这个接口时
 * 也照着这套报就行，对不上就自己给个 `message`）。
 *
 * 认不出来的码原样报数字 —— 编一个解释不如让日志里留着真的。
 */
fun sttErrorText(code: Int): String = when (code) {
    1 -> "网络超时"
    2 -> "网络不通（连不上识别服务）"
    3 -> "录音出错"
    4 -> "识别服务出错"
    5 -> "识别器用法不对"
    6 -> "没听到声音（静默超时）"
    7 -> "没听清"
    8 -> "识别器还忙着"
    9 -> "没有录音权限"
    10 -> "请求太频繁"
    11 -> "识别服务断开了"
    12 -> "识别服务不支持中文"
    13 -> "中文语言数据没下载"
    14 -> "查不了识别服务支持什么"
    else -> "错误码 $code"
}

/**
 * 连续听的重挂节拍（规格 §5 那个"每 6.2 秒一轮"就是它）。
 *
 * 纯的：**喂进时间戳，算出下一轮等多久**，所以能单测。规则就一条 ——
 * 一轮短于 `minCycleMs` 就算"没真的听起来"（识别服务不可用时 `start` 会立刻失败），
 * 退避翻倍，最长 `maxBackoffMs`；恢复正常立刻收回 `restartMs`。
 *
 * 为什么要退避：不退避就是每秒重挂五次的空转，CPU 和电量都白烧。
 */
class ListenBackoff(
    private val restartMs: Long = 200L,
    private val minCycleMs: Long = 1_000L,
    private val maxBackoffMs: Long = 5_000L,
) {

    var currentMs: Long = restartMs
        private set

    /** 一轮结束了（不管听清没听清），返回下一轮该等多久。 */
    fun onCycleEnded(startedAt: Long, endedAt: Long): Long {
        val elapsed = endedAt - startedAt
        currentMs = if (elapsed < minCycleMs) {
            minOf(currentMs * 2, maxBackoffMs)
        } else {
            restartMs
        }
        return currentMs
    }

    /** 退避着没有？（要不要打一条日志） */
    val backingOff: Boolean get() = currentMs > restartMs
}
