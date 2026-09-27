package com.fanduck.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 屏幕上的鸭子。一块全屏 WebView + 主线程 Choreographer，agent 线程只碰 DuckMotion。
 *
 * 语音（§5，按住屏幕说话 + 静音窗口）、相机（§4.1）、真云端（§4.3）都接了。
 * debug 构建右上角有一列按钮（`DuckPanel`）直接驱动 `RobotPort`，其中「下一句台词」会发一句
 * 规格 §11 的手测台词走整条链；底部一条打字输入（`TextInputBar`）—— 打字和语音进的是同一条链
 * （`onTyped` / `onHeard`），只是事件分成 text 和 heard。长按屏幕已经让给"按住说话"。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var motion: DuckMotion
    private lateinit var robot: ScreenRobot
    private lateinit var duckView: DuckView
    private lateinit var state: AgentState
    private lateinit var voice: VoiceInput
    private lateinit var sense: CameraSense

    /** 鸭子说话期间 + 说完 800 ms 内，识别结果丢弃（规格里原本没有，见 MuteWindow）。 */
    private val mute = MuteWindow()

    private var pageReady = false
    private var demoIndex = 0

    /** 跳舞（《哈基米》）。规格里没有这个功能，见 Dance.kt。 */
    private lateinit var dance: DancePlayer

    /** 当前的机位（0..7），页面重载后要按回去。 */
    private var viewIndex = 0

    /** 是不是自己打开了另一个页面（日志页）。见 `onPause`：那种 pause 不算"退到后台"。 */
    private var ownActivityOnTop = false

    /** 按住说话：按下后过了防误触的延迟才真的开麦。 */
    private var holding = false
    private val beginHold = Runnable {
        holding = true
        voice.holdOn()
    }

    private val agent: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "duck-agent")
    }

    private val ui = Handler(Looper.getMainLooper())

    /** 规格 §4.1 的每 2 秒一拍。丢给 agent 线程执行 —— 和 §4.2 挤在同一条线程上排队。 */
    private val senseTick = object : Runnable {
        override fun run() {
            agent.execute {
                val episode = senseOnce(state) { savedFrame() }
                // 只在这一拍真写了一条「看到」的时候打。没变化也每 2 秒一条会把 logcat 淹掉
                //（§4.1 第 3 条：文字没变本来就不写事件）。
                if (episode != null) Log.i(SENSE_TAG, "看到：${episode.text}")
            }
            ui.postDelayed(this, SENSE_PERIOD_MS)
        }
    }

    private val demoLines = listOf("你好", "过来", "坐下", "站起来", "看左边")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        motion = DuckMotion()
        robot = ScreenRobot(motion, this) { on ->
            mute.noteSpeaking(SystemClock.uptimeMillis(), on)
            // 手测时对表用：说话期间和说完 800 ms 内，识别结果都该被丢掉（duck-voice 会打出来）
            Log.i("duck-voice", if (on) "静音窗口 开" else "静音窗口 关（再堵 800 ms）")
        }

        val dir = File(filesDir, "agent")
        val log = EpisodeLog(File(dir, "episodes.jsonl"))
        sense = CameraSense(this)
        state = AgentState(
            cloud = cloudClient(),
            robot = robot,
            sense = sense,
            log = log,
            transcript = readTranscript(File(dir, "transcript.json")),
            episodes = log.read().toMutableList(),
            onTranscript = { writeTranscript(File(dir, "transcript.json"), it) },
            archive = { appendArchive(File(dir, "transcript-archive.jsonl"), it) },
        )

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true   // assets/duck 是本地的，不走 CDN
            setBackgroundColor(BACKGROUND)
            isLongClickable = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    // post 一下，等布局真的走完再对齐 canvas 尺寸
                    view?.post {
                        resizeAndDiagnose(web)
                        setView(web, viewIndex)     // 页面重载后把机位按回去
                        duckView.start()
                    }
                }
            }
            isLongClickable = false
        }
        // 按住屏幕说话。短按（< HOLD_DELAY_MS）什么也不做，免得手滑误触就占一次麦。
        // 这里返回 false：面板和输入条是叠在上面的独立控件，它们的点击不受影响。
        web.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.postDelayed(beginHold, HOLD_DELAY_MS)
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(beginHold)
                    if (holding) {
                        holding = false
                        voice.holdOff()
                    }
                    false
                }

                else -> false
            }
        }
        dance = DancePlayer(this)
        duckView = DuckView(web, motion) { now -> dance.frameOrNull(now) }

        val root = FrameLayout(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // 调试面板：一列按钮直接驱动 RobotPort，见 DuckPanel 的注释。
        // 规格 §6.5 的屏幕是一整块 WebView、没有控件，所以这条只在 debug 构建里挂。
        if (BuildConfig.DEBUG) {
            root.addView(
                DuckPanel(
                    this,
                    robot,
                    onNextLine = ::pushDemoLine,
                    onSnap = ::snapFrame,
                    onView = ::showView,
                    onLog = ::openLog,
                    onDance = { dance.toggle() },
                    onStop = { dance.stop() },
                ).view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.END or Gravity.TOP
                    topMargin = 24
                    rightMargin = 16
                },
            )
            // 打字入口：和语音进来的是同一条链（§4.2），只是事件记成 text
            root.addView(
                TextInputBar(this) { line -> agent.execute { onTyped(line, state) } }.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM
                    leftMargin = 24
                    rightMargin = 24
                    bottomMargin = 40
                },
            )
        }
        setContentView(root)
        // 调试入口（只在 debug 构建生效，release 忽略外部传入的 url）：
        // adb shell am start -n com.fanduck.agent/.MainActivity -e url file:///android_asset/duck/webgl-probe.html
        val url = if (BuildConfig.DEBUG) {
            intent?.getStringExtra("url") ?: DUCK_PAGE
        } else {
            DUCK_PAGE
        }
        web.loadUrl(url)

        voice = VoiceInput(this, mute) { line -> agent.execute { onHeard(line, state) } }
        val wanted = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toMutableList()
        // 前台服务那条通知（Android 13+）。不给也照样跑，只是通知栏里看不见鸭子醒着。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notify = Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(notify) != PackageManager.PERMISSION_GRANTED) wanted += notify
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val granted = permissions.indices
            .filter { grantResults[it] == PackageManager.PERMISSION_GRANTED }
            .map { permissions[it] }
            .toSet()
        if (Manifest.permission.RECORD_AUDIO in granted) voice.start()
        if (Manifest.permission.CAMERA in granted) sense.start()
    }

    /**
     * 真云端（§4.3 / §5）。`local.properties` 里没填 `duck.apiKey` 就退回演示用的假大脑，
     * 没网、没 key 也照样能跑 §11 手测。
     *
     * debug 构建可以用 intent 覆盖端点和 key，用来对着本机的假服务器跑：
     * `adb shell am start -n com.fanduck.agent/.MainActivity -e endpoint http://10.0.2.2:8765/chat/completions -e apiKey test`
     */
    private fun cloudClient(): CloudClient {
        val endpoint = debugExtra("endpoint") ?: BuildConfig.DUCK_ENDPOINT
        val apiKey = debugExtra("apiKey") ?: BuildConfig.DUCK_API_KEY
        if (apiKey.isBlank()) {
            Log.i(CLOUD_TAG, "local.properties 里没有 duck.apiKey，用演示假大脑")
            return ScriptedCloud()
        }
        Log.i(CLOUD_TAG, "云端 $endpoint，模型 ${BuildConfig.DUCK_MODEL}")
        return HttpCloudClient(endpoint, apiKey)
    }

    private fun debugExtra(name: String): String? =
        if (BuildConfig.DEBUG) intent?.getStringExtra(name) else null

    /** 规格 §4.1 第 4 条：写一条 seen 的时候顺手把这一帧覆盖写到 `seen/latest.jpg`（不上传）。 */
    private fun savedFrame(): String =
        if (sense.saveLatestFrame(File(filesDir, AGENT_DIR + "/seen/latest.jpg")) > 0) LATEST_FRAME else ""

    /** debug 面板的「拍一张」：不下发任何意图，只把最新那帧写下来，用来核对相机到底看见了什么。 */
    private fun snapFrame() {
        val size = sense.saveLatestFrame(File(filesDir, AGENT_DIR + "/seen/latest.jpg"))
        Log.i(SENSE_TAG, if (size > 0) "拍了 latest.jpg：$size 字节" else "还没有帧（相机没开或没权限）")
    }

    /** debug 面板的「日志」：打开日志页（Compose 写的，看多模态上下文和每一轮回话）。 */
    private fun openLog() {
        // 先记下来再跳：日志页盖上来会让本页 pause，那不是"退到后台"（见 onPause）
        ownActivityOnTop = true
        startActivity(Intent(this, LogActivity::class.java))
    }

    /** debug 面板的「视角」：换机位（8 个，45° 一格）。页面重载后要重新按上去。 */
    private fun showView(index: Int) {
        viewIndex = index
        if (pageReady) setView(web, index)
    }

    private fun pushDemoLine() {
        val line = demoLines[demoIndex % demoLines.size]
        demoIndex++
        agent.execute { onHeard(line, state) }
    }

    override fun onResume() {
        super.onResume()
        ownActivityOnTop = false
        if (pageReady) duckView.start()
        // 回到前台：鸭子看得见了，交回给"按住说话"，前台服务收掉。
        DuckService.stop(this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.start()
            voice.setContinuous(false)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            sense.start()
            ui.removeCallbacks(senseTick)
            senseTick.run()
        }
    }

    override fun onPause() {
        dance.stop()
        ui.removeCallbacks(senseTick)
        sense.stop()
        duckView.stop()
        // 退到后台：没人能按住屏幕了，所以切成连续听，并起前台服务让它合法（规格 §5）。
        // 服务必须**在这里**起 —— Android 12 起不允许从后台启动前台服务，onPause 这一刻还算前台。
        //
        // 但"pause"不等于"用户离开了"：打开日志页（自己的 Activity）也会 pause。那种情况
        // 按老样子把麦关掉，别在人家看日志的时候开着麦克风。
        val canHear = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (canHear && !ownActivityOnTop) {
            DuckService.start(this)
            voice.setContinuous(true)
        } else {
            voice.stop()
        }
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(senseTick)
        sense.stop()
        sense.shutdown()
        voice.stop()
        DuckService.stop(this)   // 鸭子都没了，别让前台服务留着占麦克风
        duckView.stop()
        agent.shutdown()
        robot.shutdown()
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val BACKGROUND = 0xFF0B0F14.toInt()
        const val DUCK_PAGE = "file:///android_asset/duck/index.html"
        const val REQUEST_PERMISSIONS = 1
        const val AGENT_DIR = "agent"
        const val LATEST_FRAME = "seen/latest.jpg"

        /** 规格 §4.1：每 2 秒一拍。 */
        const val SENSE_PERIOD_MS = 2_000L

        /** 按住多久才算"要说话"（防手滑误触）。 */
        const val HOLD_DELAY_MS = 250L
        const val SENSE_TAG = "duck-sense"
        const val CLOUD_TAG = "duck-cloud"
    }
}

/**
 * 演示用的假大脑。接 HttpCloudClient 之前先用它把 §11 的手测跑通：
 * 看意图里出现哪个词，就回一个对应的 Final Answer。
 */
private class ScriptedCloud : CloudClient {

    override fun complete(messages: List<Map<String, String>>): String {
        // 只看「问题：」那一行。§3.3 的意图里还嵌着「听到 / 看到 / 做过」，拿整段做
        // contains 会被记忆里的旧词劫持 —— 踩过：说「你好」，因为上次会话的记忆里有
        // 「过来」，鸭子自己走了起来（did 是 velocity，屏幕上就看不出是这一句触发的）。
        val question = messages.lastOrNull { it["role"] == "user" }
            ?.get("content").orEmpty()
            .substringAfterLast("问题：")
        return when {
            question.contains("坐下") -> reply("好，我坐下", action("sit"))
            question.contains("站起来") -> reply("站好了", action("stand"))
            question.contains("看左边") -> reply("看左边", action("gaze").put("yaw", 45))
            question.contains("过来") -> reply("我来了", action("velocity").put("vx", 0.2).put("ms", 800))
            else -> reply("你好呀", action("stop"))
        }
    }

    private fun action(name: String): JSONObject = JSONObject().put("name", name)

    private fun reply(say: String, vararg actions: JSONObject): String {
        val arr = JSONArray()
        for (action in actions) arr.put(action)
        val answer = JSONObject().put("say", say).put("actions", arr)
        return "Thought: 演示用假大脑\nFinal Answer: $answer"
    }
}
