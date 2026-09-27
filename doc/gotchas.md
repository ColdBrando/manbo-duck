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
