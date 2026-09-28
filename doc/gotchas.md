# 踩过的坑

都是断言表覆盖不到的地方 —— 单元测试全绿也照样会踩。

## JS 边界

1. **`duck.setFrame(...)` 永远报 "is not a function"**：`duck.js` 顶层有
   `const duck = new THREE.Group()`，它在全局词法环境里遮蔽了 `window.duck`。
   调用处必须写 `window.duck.setFrame(...)`，见 `DuckView.pushFrame`。
2. **canvas 是 0×0**：`renderer.setSize(window.innerWidth, ...)` 在脚本解析时执行，那时 WebView
   可能还没布局完。`duck.js` 加了一个规格里没有的 `resize()`，由 `DuckView.resizeAndDiagnose`
   在页面加载后调一次；顺带打一条 `duck diag`（`adb logcat | grep CONSOLE`）。
3. **拆硬边会新增顶点**：网格管线里按 35° 硬边拆点之后，位置数组要跟着复制一份，
   否则索引会指到数组外面，WebGL 报 `Vertex buffer is not big enough for the draw call`（画面直接空）。
4. **`alpha: false`** 是相对方块鸭方案的一处偏离。理由见 `duck.js` 里的注释：在 GL 通路不健康的
   WebView 上，`alpha:true` 的 canvas 会被整个丢弃，`alpha:false` 至少能合成出一层。

## Android / Kotlin

5. **`MediaPlayer().apply { … stop() }` 里的 `stop()` 是 `MediaPlayer.stop()`**，不是外面那个类的
   方法 —— 只停播放、不释放。表现是"音乐停了、鸭子一直跳"（`DancePlayer` 踩过）。
   要写 `this@DancePlayer.stop()`，或者干脆别用 `apply`。
6. **改了 assets 必须重新打包**：`duck.js` / `duck-meshes.js` 是打进 APK 的，直接重装旧包就是跑
   旧代码（踩过：以为相机改崩了，其实是装的旧包）。
7. **假大脑会被记忆里的旧词劫持**：演示用的 `ScriptedCloud` 原来拿整段意图做 `contains`，
   而意图里嵌着「听到 / 看到 / 做过」—— 说「你好」，因为上次会话的记忆里有「过来」，鸭子自己走了
   起来。现在只看「问题：」那一行。

## 动作（DuckMotion）

28. **躯干是根节点，整机只有 x/z/yaw 三个自由度**（`DuckFrame` 里没有俯仰/横滚），
    腿和脖子都挂在躯干**下面**。所以：
    - 想"走路时身体上下起伏"→ **做不到**。两条膝盖一起屈只把脚抬高 0.4 毫米（0.045 弧度、
      小腿才几厘米），而页面每帧会把脚底对齐回地面，一抵消身体几乎不动。
    - 想"起步时身体前倾"→ 转髋只会让两条腿在身子底下前后滑，身体纹丝不动。
    - 能看出"这只鸭子在使劲"的**只有脖子和头**（下标 5/6/8）。起伏、前探、呼吸都落在下标 5。
    方向别凭感觉：`tools/duckmesh` 里有前向运动学，几行 Python 就能量出来
    （用 `verify.py` 的 `hinge_worlds` + `decode_positions`，比渲图快得多 —— 0.06 弧度的差别
    肉眼在渲染图里根本看不出来）。
29. **下标 5（脖子）变小 = 头往 +Z（前）走**；+0.10 弧度让头往后 9 毫米，−0.10 让头往前 10 毫米。
    这是量出来的，改符号之前先重量一遍。
30. **`shot.sh` 现在能选机位**（第 4 个参数，0-7），核"往前倾/往后坐"这种必须看侧面 ——
    正面机位看不出来。`pose-probe.html` 认 `?view=`。

## 播报（TTS）

24. **"音质差"通常不是引擎不行，是嗓子选错了**。同一个引擎对中文一般有好几个 voice，
    质量从 `QUALITY_VERY_LOW` 到 `VERY_HIGH`，而 `setLanguage` 默认给的那个常常是低质量的
    "compact" 那个。挑 `quality` 最高的，同质量优先 `!isNetworkConnectionRequired`
    （联网 voice 在国内经常拉不下来，而且它一失败就是整句没声音）。实测模拟器上挑到的是
    `cmn-cn-x-ccc-local（高）`。
25. **`setLanguage` 会把 voice 复位** —— 要先 `setVoice` 再设语言，或者只设 voice（voice 里
    自带 locale）。顺序反了就是"选了半天还是那个嗓子"。
26. **`TextToSpeech.getEngines()` 是实例方法**（`android.jar` 里就是这么声明的），不是静态的。
    拿它枚举装在机器上的引擎，用来在"系统默认引擎不会说中文"时换一个试。
27. **默认的 audio attributes 是"无障碍"用途**，不少机器上音量偏小、还和自己的跳舞音乐打架。
    声明成 `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH`，说话期间再申请一个
    `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 的焦点 —— 音乐让一让，鸭子压得住。

## 长期记忆 / 提示词

21. **模型会把工具塞进 `actions` 里**。给 `remember` 加完工具之后，DeepSeek 输出的是
    `Final Answer: {"actions":[{"name":"remember","params":{...}}]}` —— 它把 remember 当成了
    机器人动作（于是被动作白名单丢掉，`did` 记一条"未知动作"）。提示词里必须明说
    **"remember 和 search_memory 一样是 Action，不是 actions 里的动作"**，改完它就照做了
    （轨迹里能看见它自己复述这句话）。加任何新工具都要防这一手。
22. **`parseReact` 里有一份工具白名单**（`TOOL_NAMES`）：只加 `executeTool` 的分支、
    忘了加白名单的话，模型调了会被判成格式错误 —— 表现是"它说记住了其实没记"，而且不报错。
23. **巩固和"没用的话"要看得出区别**：对「你好 / 过来 / 坐下」这类一次性的话，模型会正确地
    回 `[]`（不值得记），这时**水位线仍然要往前移**，否则每轮都会重复问同一批经历。

## 语音识别

18. **`SpeechRecognizer` 自己不识别**，它只是绑到设备上另一个 app 的 `RecognitionService`，
    AOSP 不带识别器。所以"本机识别"这句话里的"本机"是**设备决定的**：GMS 机器上默认那个是
    Google 的（识别在它服务器上做，音频要流上去；连不上就是错误码 2），国行 ROM 上一般是厂商的。
    查这台设备上是谁在提供：
    `adb shell cmd package query-services --brief -a android.speech.RecognitionService`，
    以及 `adb shell settings get secure voice_recognition_service`。
19. **换源必须拉黑**：两个源都"声称可用"但都失败时，不拉黑就会来回换（实测模拟器上
    on-device 报 12「不支持中文」→ 换 system → 如果 system 也报 12 → 换回 on-device → …）。
    `nextStt(…, dead = …)` 的 `dead` 是必须的，不是可选优化。
20. **端上识别在模拟器上没有中文包**（报 12），会退到系统识别（报 2，网络不通）。真机上
    要看厂商 ROM 有没有端上中文模型 —— 所以"音频不出手机"这句话，得看 logcat 里
    `识别源：…` 那条实测。

## 检测器（ML Kit）

13. **别用走 Play 服务的那个版本**（`com.google.android.gms:play-services-mlkit-image-labeling`）。
    它 APK 只涨两三兆、看着很香，但模型要现下：实测连**带 Play 服务的模拟器**都下不动 ——
    logcat 里是 `DynamiteModule: Local module descriptor class for
    com.google.android.gms.vision.ica not found`，然后每一拍都抛
    `MlKitException: Waiting for the label optional module to be downloaded`。国内真机的
    Play 服务更不可能下到。所以用 bundled 版（`com.google.mlkit:image-labeling`，模型 2.9 MB
    打进 APK），代价是必须配 `ndk.abiFilters` —— 它的 `libmlkitcommonpipeline.so` 按 4 个 ABI
    各打一份，合起来 40 MB，不配的话 debug APK 直接 67 MB。
14. **置信度不能写进「看到」那句话里**：同一幅画面每拍给出的分数都在抖（实测
    77% / 73% / 71% / 73%），文字一变就写一条新事件，`shouldWriteSeen` 的去重直接失效 ——
    10 秒里灌了 5 条同一个东西。分数字段留着做阈值和排序，别往外写。

## 打包

15. **release 包默认没有 key**，跑起来是演示假大脑 —— 这不是坏了，是 `app/build.gradle` 里
    故意的（`duck.apiKey` 只给 debug，release 只认 `duck.releaseApiKey`）。看到
    `没有云端 key，用演示假大脑` 这条 logcat 就知道是这种情况。
16. **APK 只有 arm64-v8a**。检测器带的 `libmlkitcommonpipeline.so` 按 4 个 ABI 各打一份、
    合计 40 MB，`ndk.abiFilters` 只留了 arm64（`app/build.gradle`）。**x86_64 的模拟器装不上**，
    要装就把 ABI 加回白名单。
17. **release 开了 R8**（`minifyEnabled` + `shrinkResources`）：debug 37 MB → release 19 MB。
    各家的 AAR 自带 consumer rules，`proguard-rules.pro` 现在是空的；哪天加了 keep 规则，
    **把原因也写进去**。验过一次：签名装到模拟器上能起来、不崩。

## 环境

8. **API 28 的 AOSP 镜像 WebGL 上不了屏**：最小 WebGL 探针页（`assets/duck/webgl-probe.html`，
   不依赖 three.js）也全黑，而 2D DOM 正常、GL 上下文正常（`glError=0`、9 个 draw call）、
   `readPixels` 读得出清屏色、`requestAnimationFrame` 正常。换 `-gpu swiftshader_indirect` /
   `-gpu host`、`alpha` true/false、`--disable-gpu-compositing` 都一样 —— 是这个镜像的 WebView
   合成器的问题，与本项目无关。**API 34 的 google_apis 镜像正常**。
9. **模拟器的语音识别连不上 Google**，一直报错误码 2；要看识别正文得用真机。
10. **模拟器的媒体音量可能是卡住的**（实测 `streamVolume:5` 改不动，`cmd media_session volume --set`
    和音量键都无效）—— 声音小只能调宿主机那一侧。
11. **改过 AVD 的硬件配置之后要冷启动**（`-no-snapshot-load`）：直接开机会加载旧快照，
    qemu 会段错误（`detected a hanging thread 'QEMU2 CPU0..3 thread'`）。
12. **宿主键盘进不去客机**：AVD 的 `hw.keyboard` 要是 `no`，客机里连键盘设备都没有。
    改成 `yes` 之后 `dumpsys input` 里会多出 `qwerty2 (aka device 0 - built-in keyboard)`。
