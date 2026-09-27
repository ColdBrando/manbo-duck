# duckApp — 屏幕上的鸭子（本地 agent）

**规格文档是本地契约，不在这个仓库里**（它带着大量内部决策记录，见 `.gitignore`）：
类型、函数、系数、动作白名单都以它为准，代码里凡是"按规格实现"的地方都标了 §节号。
仓库里的 README（含 `tools/duckmesh/README.md`）是能公开的那部分说明。

## 工程结构

```
core/           纯 Kotlin/JVM 模块 —— 规格第 1 节里可单测的那部分
app/            Android application —— 只放适配层（WebView / TTS / 相机 / 语音 / HTTPS）
tools/duckmesh/ 屏幕鸭子的网格生成管线（离线跑，不进 APK）
```

为什么把逻辑做成 JVM 模块而不是 Android library：规格要求"单测只测 Episode.kt 到 AgentLoop.kt，
再加上 DuckMotion.kt"，做成 JVM 模块后 `./gradlew :core:test` 不需要 SDK、模拟器或设备，
24 条断言 41 ms 跑完。`app` 模块除了日志页那套 Compose（见下），没有任何第三方依赖。

`org.json` 在 `core` 里是 `compileOnly` + `testImplementation`：Android 运行时自带它，
不重复打进 APK；JVM 单测用的是真实现，绕开规格 §3.4 说的"Android 单测里 org.json 是空壳"。

```
core/src/main/kotlin/com/fanduck/agent/
  Episode.kt       事件读写、id、截断（含只读尾部 N 条的保留策略）
  MemorySearch.kt  分词、打分、按类挑选            §3.1 §3.2
  IntentText.kt    拼第一次发给云端的用户消息        §3.3
  ReactParse.kt    解析 Thought / Action / Final    §3.4
  Actions.kt       白名单、裁剪、近距离拒绝          §3.5
  Transcript.kt    轨迹追加、压缩切割、落盘          §3.6
  AgentLoop.kt     语音队列和 ReAct 主循环           §4   §7
  DuckMotion.kt    意图 → 15 个关节角和地面位置      §6.3
  Dance.kt         《哈基米》舞步：八拍一循环的关键帧 + 插值（规格里没有，见 §13）
  SenseLoop.kt     看见一句怎么写 + 每 2 秒一拍（纯部分）§4.1
  MuteWindow.kt    静音窗口：说话期间和说完 800 ms 内丢掉识别结果  §5（补的）
  CloudStream.kt   云端的线格式：请求体怎么拼、SSE 里正文取哪个字段  §4.3
  RobotPort.kt     RobotPort / CloudClient / SensePort  §5
app/src/main/kotlin/com/fanduck/agent/
  MainActivity.kt  全屏 WebView + agent 线程 + 云端选择 + 静音窗口  §4 §7
  ScreenRobot.kt   RobotPort 的手机实现：DuckMotion + TextToSpeech   §5 §6.4
  VoiceInput.kt    本机语音识别，一句一挂 → 过静音窗口 → onHeard    §5
  CameraSense.kt   SensePort 的手机实现：Camera2 取帧 + 接近传感器   §4.1 §5
  DancePlayer.kt   放《哈基米》并驱动舞步（MediaPlayer + 拍子取帧）
  HttpCloudClient.kt  CloudClient 的手机实现：SSE 流式 + 8 秒超时 + 重试  §4.2 §4.3 §5
  DuckView.kt      界面时钟 30 fps + pushFrame                       §6.4 §6.5
  DuckPanel.kt     debug 构建的调试面板：一列按钮直接驱动 RobotPort（规格里没有，见 §13）
  TextInputBar.kt  debug 底部的打字输入（走 onHeard）
  LogActivity.kt   日志页 **Compose 写的**（上下文 / 事件 / 轨迹），app 模块唯一的第三方依赖
app/src/main/assets/duck/
  index.html        duck.js  three.min.js（r160，最后一个带 UMD 构建的版本，不联网）
  duck-meshes.js    真机 microduck 的网格 + 关节表，**生成文件**（tools/duckmesh/build.py）
tools/duckmesh/     网格生成管线（Python + Node/meshoptimizer），见它自己的 README
```

## 屏幕上的鸭子用的是真机模型

`duck.js` 里那只鸭子不是方块拼的，是 pollen-robotics/microduck 的真机 STL：
`tools/duckmesh/build.py` 读 `microduck_rl` 的 MJCF + 43 个 STL，装配成 16 个铰链、
68 个零件、36 个网格，简化到 9.7 万三角形（重复零件另算），base64 内联成 2.5 MiB 的
`duck-meshes.js`。改模型就重跑 `python3 tools/duckmesh/build.py`，`verify.py` 负责验证。

几个要点（细节在 `tools/duckmesh/README.md`）：

* **关节符号不是猜的**：`DUCK_MODEL.signs` 从真机轴线量出来。把 `DuckMotion` 的 STAND
  原样喂进真机骨架，脚底落在 +2.8 mm（就是站着）；符号全取反则浮空 3.8 cm。
* **右腿不取反**：规格 §6.5 那句"右腿下标 11-14 取反"是给方块鸭写的外观补丁，真机左右腿
  各有一套带符号的舵机约定。
* **端到端校验**：`verify.py` 读生成好的资产、按 duck.js 的算法复算 STAND/SIT/WALK，
  和 MuJoCo 前向运动学比，最大偏差 0.03 mm。这覆盖了 README 之前列的待决项 6。
* **自动贴地**：贴地偏移只能按一个姿态烘（STAND），而真机坐下时躯干会降 5.5 cm，
  所以 duck.js 每帧按脚部网格最低点补一个竖直偏移。
* **正面机位 + 软跟随**：鸭子正对观众固定在屏幕中间，可以先走出去 5 cm（看得见在走），
  镜头再平滑追回来，偏离上限是硬的 —— 一步 16 cm，固定斜机位那版走两步就出画了。

## 许可

* **代码**：Apache-2.0（见 [LICENSE](LICENSE)）。
* **模型网格** `app/src/main/assets/duck/duck-meshes.js`：**CC BY-SA-NC** —— 从
  [pollen-robotics/microduck](https://github.com/pollen-robotics/microduck) 的模型文件生成，
  声明见同目录的 `duck-meshes.LICENSE.txt`。自用没问题；**对外分发 / 售卖 / 上架之前要先解决
  NC 这一条**。

> **授权**：真机 3D 模型文件是 **CC BY-SA-NC**（上游 `microduck_rl/README.md` 声明），
> 所以 `duck-meshes.js` 也是这个许可，声明随文件放在 `duck-meshes.LICENSE.txt`。
> 自己玩没问题；**对外分发 / 售卖 / 上架之前要先解决 NC 这一条**（见 tools/duckmesh/README.md）。

## 让鸭子跳《哈基米》

调试面板上按「跳舞」（第 13 个按钮）。曲子 `assets/dance/hakimi.mp3` 24.3 s、**122 BPM**，
一拍 492 ms，八拍 3.936 s 一循环；动作是"帝皇舞步"那个感觉：原地高抬腿踏步、左右倾、点头、
跟拍张嘴，身体还带一点横移和扭腰。

- 舞步数据在 `core/Dance.kt` 的 `hakimiDance()`（一张八拍表，改舞只改这里），
  渲染方向是拿 `tools/duckmesh/shot.sh` 一张张看出来的，不是猜的。
- 再按一次「跳舞」或按「停」收舞；曲子放完自动收，收舞时 250 ms 混回站立。

## 日志页（Compose）

debug 面板上按「日志」打开。数据全部读 `filesDir/agent/` 下已经落盘的文件，所以重启后照样能翻。
三个页签：

| 页签 | 看什么 |
|---|---|
| **上下文** | `pack(transcript)` 的原样 —— 这一轮真会发出去的消息（系统提示词 + 记忆块 + 轨迹）。**图片不在里面**（§8 不上传媒体），所以图片单独在「事件」看 |
| **事件** | `episodes.jsonl` 原始流（听到 / 看到 / 做过 + 时间 + id），最上面是最近留存的那一帧 |
| **轨迹** | `transcript.json` 的消息 + 上一段轨迹的摘要 + 归档条数和各文件大小 |

用 Compose 写的：`androidx.compose:compose-bom:2023.10.01` + `activity-compose:1.8.1`（Kotlin 1.9.20
配编译器 1.5.4）。这是 `app` 唯一的第三方依赖，代价是 **debug APK 从 4.1 MB 涨到 ~19.5 MB**
（没开混淆）。要瘦下来就在 release 上开 `minifyEnabled true`，或者把这个页面挪出主包。

## 构建

```bash
./gradlew :core:test          # 规格 §10 的断言表，秒级
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
./gradlew build               # 含 lint
```

版本组合：Gradle 8.2 + AGP 8.2.0 + Kotlin 1.9.20 + compileSdk 34 / minSdk 28 / targetSdk 34。
改 `app/src/main/assets/` 里的东西（`duck.js`、`duck-meshes.js`）也必须重新 `assembleDebug`：
assets 是打进 APK 的，直接重装旧包就是跑旧代码（踩过：以为相机改崩了，其实是装的旧包）。

Compose 那套依赖本机缓存里基本都有，只差两个 jar（`concurrent-futures` / `listenablefuture`）——
联网跑过一次 `assembleDebug` 补齐了，之后 `--offline` 照样能构建。换机器构建时要联网拉一次。
这套版本在本机 Gradle 缓存里已存在，可离线构建。`local.properties` 里是 `sdk.dir=/Volumes/macos/android`。

## 现在能做什么

三个适配层都接了（语音 / 相机 / HTTPS），§9 的实现顺序走完了。当前可以在设备上核对：

- **真云端**（DeepSeek，OpenAI 兼容）：端点、模型、key 在 `local.properties`：
  ```properties
  duck.endpoint=https://api.deepseek.com/chat/completions
  duck.model=deepseek-chat
  duck.apiKey=sk-...        # 空着就退回演示假大脑 ScriptedCloud，没网也能跑 §11 手测
  ```
  key 会明文编进 `BuildConfig` —— 自用没问题，构建产物别给别人。logcat 里 `duck-cloud`
  能看到发出的端点和每一轮的失败原因（8 秒超时 / 401 / 连不上）。

- **按住屏幕说话**（本机识别，`SpeechRecognizer` → `onHeard`）：按住 ≥250 ms 开麦，松开收尾，
  识别结束才把这一句交给主循环。短按不会开麦（防误触）。闲置时识别器不工作 ——
  之前"一直在听"的版本每 6 秒要重挂一次，系统的录音提示音一直响（见规格 §5 / §13）。
  鸭子说话期间和说完 800 ms 之内识别结果会被丢掉（静音窗口）—— 不堵的话它会听到自己。
  logcat 里 `duck-voice` 能看到「按住 / 松开」「听到：xxx」「静音窗口内，丢弃：xxx」。
- **相机每 2 秒一拍**（`CameraSense`，Camera2+`ImageReader`）：没有检测器，所以那句话就是
  `摄像头正常，未识别物体`；盖住听筒（接近传感器）会拼上"最近距离 0 米"，于是 §3.5 的
  近距离拒绝生效、鸭子不肯往前走。`duck-sense` 只在**真写了一条「看到」**的时候打日志
  （文字没变化本来就不写事件，也就不打）—— 闲着的 logcat 是干净的。
  debug 面板的「拍一张」会把当前帧存到 `files/agent/seen/latest.jpg`（不上传），
  `adb exec-out run-as com.fanduck.agent cat files/agent/seen/latest.jpg > x.jpg` 拉出来看。

- **debug 构建右上角有一列按钮**（`DuckPanel`）：站 / 坐 / 看左 / 看右 / 走一步 / 退一步 /
  停 / 说直接驱动 `RobotPort`（不写事件、不走 agent 循环），「拍一张」存一帧
  `seen/latest.jpg`，「视角」换 8 个机位（绕鸭子 45° 一格，1 = 正前方、3 = 侧面、5 = 正后方），
  「下一句台词」走整条链。按了会往 logcat 打一条 `duck-panel`。
  规格 §6.5 的屏幕是一整块 WebView、没有控件，所以这个面板只在 debug 里挂。
- **debug 构建底部有一条打字输入**（`TextInputBar`）：打一句、回车（或点发送），走的是
  `onHeard` —— 和语音进来的是同一条链（拼意图 → 云端 → 动作 + TTS 出声 + `did` 落盘）。
  语义上打字暂时按「听到」记：事件是 `heard`、意图里写「语音：xxx」（§2 只有 heard/seen/did
  三类）。真接了语音识别以后，这条要么删掉，要么规格里加一个 `Kind.TEXT`。
- 长按屏幕或按**音量下键**，会顺序发一句规格 §11 的手测台词
  （你好 / 过来 / 坐下 / 站起来 / 看左边），由 `MainActivity.ScriptedCloud` 这个假大脑回一个
  Final Answer。可以核对：站立、迈步并停在新位置、坐站 2 秒插值、转头、张嘴说话、did 落盘。

> 假大脑**只看意图里「问题：」那一行**。它原来拿整段做 `contains`，被「听到 / 看到 / 做过」
> 里的旧词劫持过：说「你好」，因为上次会话的记忆里有「过来」，鸭子自己走了起来。

开机时 `episodes.jsonl` 和 `transcript.json` 会从 `filesDir/agent/` 读回来。

## 上屏踩过的坑（都是断言表覆盖不到的 JS 边界）

1. **`duck.setFrame(...)` 永远报 "is not a function"**：`duck.js` 顶层有
   `const duck = new THREE.Group()`，它在全局词法环境里遮蔽了 §6.5 末尾注册的 `window.duck`。
   调用处必须写 `window.duck.setFrame(...)`，见 `DuckView.pushFrame`。
2. **canvas 是 0×0**：`renderer.setSize(window.innerWidth, ...)` 在脚本解析时执行，那时 WebView
   可能还没布局完。`duck.js` 加了一个规格里没有的 `resize()`，由 `DuckView.resizeAndDiagnose`
   在页面加载后调一次。顺带它会在 logcat 打一条 `duck diag`（`adb logcat | grep CONSOLE`）。
3. **`MediaPlayer().apply { … stop() }` 里的 `stop()` 是 `MediaPlayer.stop()`**，不是外面那个类的
   方法 —— 只停播放、不释放，表现是"音乐停了、鸭子一直跳"（`DancePlayer` 踩过）。要写
   `this@DancePlayer.stop()`，或者干脆别用 `apply`。
4. `alpha: false` 是相对规格的一处偏离（规格写 `alpha: true`）。理由见 `duck.js` 里的注释：
   在 GL 通路不健康的 WebView 上，`alpha:true` 的 canvas 会被整个丢弃，`alpha:false` 至少能合成。
   真机上两者是否等价还没验证。

## 模拟器限制（本机 API 28 AOSP 镜像）

**这个镜像的 WebView 无法把 WebGL 内容合成上屏**，与我们的代码无关：

- 最小 WebGL 页面 `assets/duck/webgl-probe.html`（不依赖 three.js）也是全黑；
- 2D DOM 正常（改色能刷新）；GL 上下文正常（`glError=0`、9 个 draw call、492 三角形）；
  `readPixels` 读得出清屏色；`requestAnimationFrame` 178 帧/3 秒；
- 试过 `-gpu swiftshader_indirect` / `-gpu host`、`alpha` true/false、`--disable-gpu-compositing`，
  均一样。`alpha:false` 时 canvas 会合成出一个**黑**层（不是背景色），说明 canvas 上屏了、
  但 GL 的共享纹理内容进不去。

**要看鸭子动，得用真机**（或换 API 34+ 的 system image）。在真机上先用
`webgl-probe.html` 确认 WebView 健康：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.fanduck.agent/.MainActivity -e url file:///android_asset/duck/webgl-probe.html
# 屏幕左半应是品红、右半青色。然后不带 -e 参数启动就是鸭子。
```

## 还没做 / 待决策

规格 §9 的实现顺序已经走完（八个模块都在），剩下的都是评审里明确留下、等拍板的：

1. ~~**TTS 自激回环**：鸭子说话时麦克风会听到自己 → `onHeard` → 云端 → 再说。~~
   **已解决**：静音窗口（规格 §5 补的那条 + `MuteWindow`），说话期间和说完 800 ms 内的
   识别结果都丢掉。
2. **`compactIfNeeded` 的云调用不在 try 里**（`AgentLoop.kt`）：压缩超时会把异常穿到语音回调。
3. **轮内压缩会把本轮意图挤出 keep-6**：第 6 条消息之后模型看不到原始问题。
4. **`RobotPort` 没有返回通道**：真机拒绝意图回不来，did 只能说 ok。
5. §4.1 的 seen 去重规则与 §1/§11 冲突，当前按 §4.1 的算法实现。
6. ~~§6.2 的符号约定没有任何断言覆盖。~~ **已解决**：见 `tools/duckmesh/verify.py`，
   结论和规格 §6.5 相反 —— 真机模型**右腿不取反**。
7. **麦克风只在鸭子看得见时开**：规格 §5 说的"前台服务持有麦克风"（退到后台也听）还没做。
8. **打字进来算「听到」**：`TextInputBar` 走 `onHeard`，事件记成 `heard`、意图写「语音：xxx」
   （规格 §2 只有 heard/seen/did 三类）。要不要加一个 `Kind.TEXT` 由你定。

## Android 侧的已知事项

- **麦克风只在 Activity 可见时开**（`onResume` 开、`onPause` 关）。规格 §5 说的"前台服务持有
  麦克风"（退到后台也听）**还没做** —— 要做就得连带处理 `foregroundServiceType="microphone"`
  的通知、`POST_NOTIFICATIONS`，以及 Android 11+ 不能从后台启动麦克风前台服务这条限制。
- 屏幕常亮已加（`FLAG_KEEP_SCREEN_ON`）。Activity 进后台时 Choreographer 会停，鸭子停步。
- 音量下键的演示触发已删（语音接上了，不再占着音量键）；长按屏幕现在也是"按住说话"，
  §11 的演示台词只剩 debug 面板的「下一句台词」。
