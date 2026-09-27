# 架构

## 两个模块，一条边界

```
core/            纯 Kotlin/JVM —— 可单测的那部分（不碰 Android API）
app/             Android application —— 只放适配层（WebView / TTS / 相机 / 语音 / HTTPS）
tools/duckmesh/  屏幕鸭子的网格生成管线（离线跑，不进 APK）
```

为什么把逻辑做成 JVM 模块而不是 Android library：单测只该覆盖事件、记忆检索、ReAct 主循环、
轨迹压缩和姿态计算这几块，做成 JVM 模块后 `./gradlew :core:test` 不需要 SDK、模拟器或设备，
56 条断言秒级跑完。`app` 模块只有两个第三方依赖：日志页那套 Compose，和检测器的
ML Kit（`image-labeling`，模型随 APK，不联网，见 `doc/features.md`）。

`org.json` 在 `core` 里是 `compileOnly` + `testImplementation`：Android 运行时自带它，不重复打进
APK；JVM 单测用的是真实现（Android 的本地单测里 `org.json` 是空壳，很多行为不一样）。

## 一块屏幕到一朵云的数据流

```
按住说话 ──► SpeechRecognizer ──►（静音窗口：鸭子说话时丢掉）──► onHeard
打字输入 ──► onTyped ────────────────────────────────────────────┘
                                                                    │
相机每 2 秒一拍 ──►「看到」一行 ─┐                                   │
做过的事 ──►「did」一行 ────────┴──► episodes.jsonl（全在本机）      │
                                                                    ▼
                                    拼意图：语音/打字 + 听到 + 看到 + 做过 + 问题
                                                                    │
                                          DeepSeek（SSE 流式，8 秒无 token 报错）
                                                                    │
                              ReAct：Thought / Action / Final Answer（最多 8 步）
                                                                    │
                     动作白名单 + 裁剪 + 近距离拒绝 ──► RobotPort ──► 屏幕上的鸭子
                                                                    │           │
                                                        每条动作落一条 did ◄── Ack
```

## core 的文件

| 文件 | 干什么 |
|---|---|
| `Episode.kt` | 事件读写、id、按类截断（读盘只取尾部 N 条，免得跑一天越来越慢） |
| `MemorySearch.kt` | 分词、打分、按类挑选：中文按字 + 英文数字按词 |
| `IntentText.kt` | 拼第一次发给云端的那段用户消息 |
| `ReactParse.kt` | 解析 `Thought` / `Action` / `Final Answer` |
| `Actions.kt` | 动作白名单、速度裁剪、近距离拒绝 |
| `Transcript.kt` | 轨迹追加、压缩切割、落盘 |
| `AgentLoop.kt` | 语音队列和 ReAct 主循环、工具（`search_memory` / `look_now`） |
| `DuckMotion.kt` | 意图 → 15 个关节角 + 地面位置，30 fps 采样 |
| `SenseLoop.kt` | 「看见一句怎么写」的纯部分 + 每 2 秒一拍 |
| `Detector.kt` | 检测结果怎么变成一句话：阈值、取几个、中英标签表（认图本身在 :app） |
| `MuteWindow.kt` | 静音窗口：说话期间和说完 800 ms 内丢掉识别结果 |
| `CloudStream.kt` | 云端线格式：请求体怎么拼、SSE 里正文取哪个字段 |
| `Dance.kt` | 《哈基米》舞步：八拍一循环的关键帧 + 插值 |
| `RobotPort.kt` | `RobotPort` / `CloudClient` / `SensePort` 三个接口（唯一长期不变的契约）；运动类方法回 `Ack`，真机拒绝的原因进 `did` |

## app 的文件

| 文件 | 干什么 |
|---|---|
| `MainActivity.kt` | 全屏 WebView + agent 线程 + 云端选择 + 静音窗口 + 生命周期 |
| `ScreenRobot.kt` | `RobotPort` 的手机实现：`DuckMotion` + `TextToSpeech`，说话时同时张嘴 |
| `DuckView.kt` | 界面时钟（约 30 fps）+ `window.duck.setFrame` + 机位切换 |
| `VoiceInput.kt` | 本机语音识别：前台按住说话、后台连续听（带退避），一律过静音窗口 → `onHeard` |
| `DuckService.kt` | 前台服务（`microphone`）：鸭子退到后台时把麦克风握在手里，通知点一下回应用 |
| `CameraSense.kt` | `SensePort` 的手机实现：Camera2 取帧 + ML Kit 认图 + 接近传感器 |
| `HttpCloudClient.kt` | `CloudClient` 的手机实现：SSE 流式 + 8 秒超时 + 连接失败重试 |
| `DancePlayer.kt` | 放《哈基米》并驱动舞步（MediaPlayer + 按拍子取帧） |
| `DuckPanel.kt` | 调试面板：一列按钮直接驱动 `RobotPort`（只在 debug 构建） |
| `TextInputBar.kt` | 打字输入（只在 debug 构建，走和语音同一条链） |
| `LogActivity.kt` | 日志页，Compose 写的（上下文 / 事件 / 轨迹） |

## assets

```
app/src/main/assets/duck/
  index.html        duck.js  three.min.js（r160，最后一个带 UMD 构建的版本，不联网）
  duck-meshes.js    真机 microduck 的网格 + 关节表，**生成文件**（tools/duckmesh/build.py）
  webgl-probe.html  最小 WebGL 探针（判断一台设备的 WebView 健不健康）
app/src/main/assets/dance/
  hakimi.mp3        跳舞用的曲子
```

## 线程

- **界面时钟**（Choreographer，主线程，约 30 fps）：采样姿态 → `evaluateJavascript` 推给 WebView。
  这不是电机循环。
- **agent 线程**（单线程 executor）：所有云端调用、工具执行、事件落盘都在这一条线程上排队，
  包括每 2 秒的相机一拍。语音回调和相机回调只把结果丢进来，不在回调里访问云端。
- **相机回调**在自己的 `HandlerThread` 上，只往两个 volatile 字段写值。
