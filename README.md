# manbo-duck — 屏幕上的鸭子

手机上的鸭子 agent。**屏幕上的那只 3D 鸭子就是它的身体**：它看得见（相机）、听得见（按住说一句）、
记得住（事件全在本机）。一句话之后，本机把"听到 / 看到 / 做过"拼成一段意图发给云端模型，
模型用 ReAct 决定做什么，鸭子就在屏幕上走过来、坐下、转头或者张嘴说话。

云端只收到文字、只下发意图（`stop` / `velocity` / `gaze` / `stand` / `sit`），**不写关节角、
不存记忆、不收媒体字节**。这是 [Fanduck](https://github.com/7757/fanduck) 的屏幕版复刻，
3D 模型来自 [pollen-robotics/microduck](https://github.com/pollen-robotics/microduck) 那台真机。

![屏幕上的鸭子](doc/media/screenshot.png)

▶ [演示视频（webm，点开看）](doc/media/demo.webm)

## 它能干什么

- **说话**：按住屏幕说一句（本机识别，音频不上传），或者用调试面板底部的打字框。
  鸭子说话期间和说完 800 ms 内的识别结果会被丢掉（静音窗口）—— 不堵的话它会听到自己。
- **看得见**：每 2 秒取一帧（Camera2）+ 接近传感器，写成一句「看到」；没有检测器时就是
  `摄像头正常，未识别物体`。图片只留在手机上，不上传。
- **记得住**：听到 / 打字 / 看到 / 做过的每一件事都落在 `episodes.jsonl`，按分词打分检索出最相关的
  几条拼进意图；轨迹太长会压缩成摘要。
- **会动**：15 个关节角 + 地面位置，30 fps 推到 WebView 里的 three.js。站、坐、走、转头、张嘴，
  外加一段《哈基米》的舞（122 BPM，八拍一循环）。
- **能查**：一个日志页，把「这一轮发给云端的全部内容」「模型每一轮的原话」「拍到的帧」摊开看。

## 现在到哪一步

屏幕鸭子这一版**功能已经跑通**（下面这一列都是跑过的，不是设想）：

| | 状态 |
|---|---|
| 纯逻辑层（事件 / 记忆检索 / ReAct 主循环 / 轨迹压缩 / 姿态） | ✅ `./gradlew :core:test` 49 条断言，秒级 |
| 屏幕上的鸭子（真机 microduck 网格，9.7 万三角形） | ✅ 8 个观察机位、自动贴地、正面软跟随机位 |
| 语音输入 | ✅ 按住说话 + 静音窗口 |
| 相机 + 距离 | ✅ 每 2 秒一拍，盖住听筒会触发近距离拒绝 |
| 真云端 | ✅ DeepSeek（OpenAI 兼容）SSE 流式，8 秒无 token 报错、连接失败重试 1 次 |
| 调试面板 / 打字输入 / 日志页 / 跳舞 | ✅ 面板与输入条只在 debug 构建里挂 |

## 还差哪些

1. **前台服务持有麦克风**：现在只在鸭子可见（Activity 在前台）时开麦，退到后台就不听了。
2. **接真机**：`RobotPort` 目前由 `ScreenRobot` 实现（屏幕 + TTS）。接口已经有返回通道
   （运动类方法回 `Ack`，真机拒绝的原因会落进 `did`），但**真机实现本身还没有** ——
   真机的控制环在另一个仓库里（`robotd` 的 50 Hz + ONNX 策略），得先把它拿过来。
3. **还没有检测器**：相机那句话是固定的，模型看不到像素，只知道"镜头正常"。

## 跑起来

```bash
./gradlew :core:test          # 纯逻辑断言，不需要 SDK、模拟器或设备
./gradlew :app:assembleDebug  # → app/build/outputs/apk/debug/app-debug.apk
```

云端配置放在 `local.properties`（这份文件不进版本库）：

```properties
duck.endpoint=https://api.deepseek.com/chat/completions
duck.model=deepseek-chat
duck.apiKey=sk-...        # 留空就退回演示用的假大脑，没网也能跑
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.fanduck.agent/.MainActivity
```

三个容易踩的：

- 改 `app/src/main/assets/` 里的东西（`duck.js`、`duck-meshes.js`）**也必须重新 `assembleDebug`**：
  assets 是打进 APK 的，直接重装旧包就是跑旧代码。
- 模拟器：**API 28 的 AOSP 镜像 WebGL 上不了屏**（鸭子全黑，与本项目无关，最小 WebGL 探针页
  也一样黑）；API 34 的 google_apis 镜像正常。
- 语音识别在模拟器上连不上 Google（错误码 2），要有正文得用真机。

## 目录

```
core/            纯 Kotlin/JVM：事件、记忆检索、ReAct 主循环、姿态计算、静音窗口、舞步
app/             Android 适配层：WebView 鸭子 / TTS / 语音 / Camera2 / DeepSeek HTTPS / Compose 日志页
tools/duckmesh/  离线网格管线：microduck 的 MJCF + STL → duck-meshes.js（见它自己的 README）
doc/             细节文档（见下）
```

细节都在 `doc/` 下：[架构](doc/architecture.md) · [屏幕上的鸭子](doc/duck-model.md) ·
[功能细节](doc/features.md) · [踩过的坑](doc/gotchas.md)

## 许可

* **代码**：Apache-2.0，见 [LICENSE](LICENSE)。
* **模型网格** `app/src/main/assets/duck/duck-meshes.js`：**CC BY-SA-NC** —— 从
  [pollen-robotics/microduck](https://github.com/pollen-robotics/microduck) 的模型文件生成，
  声明见同目录的 `duck-meshes.LICENSE.txt`。自己玩没问题；**对外分发 / 售卖 / 上架之前要先解决
  NC 这一条**。
* **音乐** `app/src/main/assets/dance/hakimi.mp3`：随仓库提供的演示素材。
