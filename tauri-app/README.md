# duck 桌面壳（Tauri）

把 Android 那边 `app/src/main/assets/duck/` 那套页面**原样**装进一个桌面窗口。
一只站在屏幕上的鸭子，和手机上那只长得一模一样 —— 因为它就是同一份文件。

现在做两件事：**能看见**（窗口开起来、鸭子渲染出来、拖动窗口画面跟着重排）和
**会走**（播真机策略烘出来的步态，方向键驱动）。没有语音、没有云端、没有 agent 循环，
那些还在 Android 那边。

## 键盘

| 键 | 干什么 |
|---|---|
| `↑` / `W` | 往前走（0.4 m/s） |
| `↓` / `S` | 往后退 |
| `←` / `A` | 左转（原地，1.0 rad/s） |
| `→` / `D` | 右转 |
| `空格` | 停（把所有按键放掉） |

窗口失焦会把按键全放掉，不会"卡着一直走"。

注意**转身的时候腿是不走的**：素材里只有一段"前进"的片段，`pickClip` 按方向挑，
纯转身（vx=0）配不上它，所以鸭子原地转、腿保持站姿。这和 Android 那边
`GaitPack.forCommand` 的行为一致 —— 要"边走边转"就得再录一段带 `wz` 的素材。

## 跑起来

```bash
npm install          # 只装 @tauri-apps/cli
npm run dev          # 开发模式，改 Rust 会自己重编
npm run build        # 出 .app / .dmg（macOS），产物在 src-tauri/target/release/bundle/
```

前置：Rust 工具链（`rustc` / `cargo`）+ Xcode Command Line Tools。本机装 Rust 时直连
`static.rust-lang.org` 会断，走的是清华镜像：

```bash
RUSTUP_DIST_SERVER=https://mirrors.tuna.tsinghua.edu.cn/rustup \
  rustup toolchain install stable --profile minimal
```

## 目录

```
tauri-app/
  package.json          只有 @tauri-apps/cli 一个依赖，没有前端框架、没有打包器
  src-tauri/
    Cargo.toml
    tauri.conf.json     frontendDist 指到 Android 的 assets，见下
    capabilities/       窗口权限（这一版是纯静态加载，只要 core:default）
    icons/              由 `npx tauri icon` 生成
    src/lib.rs          开窗口 + 注入一段 resize 脚本，全部逻辑就这些
    src/main.rs         仅调用 lib.rs
```

**没有 `dist/`，也没有 `index.html`** —— 前端资源不在这个目录里。

## 为什么不复制一份 assets

`tauri.conf.json` 里：

```json
"build": { "frontendDist": "../../app/src/main/assets/duck" }
```

路径相对于 `src-tauri/`，所以指回的是 Android 那份 assets。Tauri 在这一点上就是个
**静态 Web 宿主**：给它一个目录，它用系统 WebView 渲染里面的 `index.html`，
和 Android 那边 `WebView` 干的是同一件事。

这么接的收益是 `duck.js` / `duck-meshes.js`（2.6 MB 的生成文件）全项目只有一份，
改完手机和桌面同时生效，不会出现"手机上是新的、桌面还是旧的"。代价是
`tauri-app/` 不能单独拷走 —— 它对 `../app/` 有依赖。

顺带一提，`duck.js` 里那句"three.min.js 必须在 assets 里：离线手机打不开 CDN"、
以及用 `<script>` 标签而不是 `fetch` 取 base64（躲 WebView 的 file:// CORS），
在 Tauri 这边同样成立 —— 这些为 WebView 做的判断，桌面壳不用改一行。

## 唯一一处"不是原样"：resize

窗口是建在 Rust 里（`src/lib.rs`）而不是写在 `tauri.conf.json` 的 `app.windows` 里，
就为了能挂一段 `initialization_script`：

```js
window.addEventListener('resize', () => window.duck.resize());
```

原因：`duck.js` 只在**解析时**调一次 `renderer.setSize(window.innerWidth, ...)`，
WebGL canvas 的尺寸是那一次写死的，之后窗口被拖动它不会跟着变 —— 画面停在旧尺寸、
周围留一圈黑边。Android 那边靠 `DuckView` 在页面加载后调一次 `window.duck.resize()`
解决（见 `duck.js` 里"规格里没有这个函数"那条注释）；桌面窗口能随时拖，
所以这里挂到 resize 事件上。

写成**注入脚本**是为了不动 assets 里任何一个文件。

两个注意点，都踩过：

1. 必须写 `window.duck` 而不是 `duck`。`duck.js` 顶层有 `const duck = new THREE.Group()`，
   它在全局词法环境里遮蔽了 `window.duck`（`doc/gotchas.md` 第 1 条）。
2. 初始化脚本在页面脚本**之前**执行，那时 `window.duck` 还不存在，所以判空、且只在事件里调。

## 步态播放器

`src-tauri/src/gait-player.js`（`include_str!` 编译进二进制，和 resize 脚本拼成同一段
初始化脚本注入）。它读 `app/src/main/assets/duck/gaits.json` —— 就是 Android 那边
`Gait.kt` 读的同一份文件 —— 按指令积分出地面位置，取片段的一帧摊到 15 个关节角上，
30 fps 推给 `window.duck.setFrame(...)`。

**素材归 Android 那条线**：`gaits.json` 由 `tools/duckgait/bake_gait.py` 从真机策略
（`microduck-policies` 的 `velstand.onnx`）在 MuJoCo 里录的轨迹烘出来。桌面这边**只读**，
不生成也不改写 —— 所以手机和桌面上鸭子走的永远是同一套步态。

### 和 `DuckMotion.kt` 的差异（有意为之）

1. **没有实现手写步态兜底**（膝盖摆动 / 踏步 / 前倾）。那部分是给"没有素材"兜底的，
   而真机的 `velstand` 策略在低于 `WALK_CLIP_MIN`(0.3 m/s) 时本来就选择站着 ——
   所以这里低于门槛就站着呼吸，反而和真机行为一致。也避免把另一个会话正在改的 Kotlin
   逻辑抄第二遍、两边各自漂移。
2. **命令速度定死在 0.4 m/s**（`WALK_SPEED`）。烘出来的那段 clip 就是 0.4 m/s 录的，
   步频是那段录制的固有属性；命令成别的速度、腿还按 0.4 的频率倒，就会"滑行"——
   正是这个项目反复修掉的那个观感。**想要更快（"跑"）得按更高指令再录一段烘进来**，
   不是在播放器里加速度。
3. **稳在 30 fps**（累加器步进）。不只是省 CPU：`duck.js` 的相机软跟随是**按帧**追
   `CAM_LAG` 6%，60 fps 下时间常数会短一半，观感就和手机上不是同一只鸭子了。

匹配的部分：`ACCEL_TAU` / `WALK_CLIP_MIN` / `CLIP_FADE_S` / `BREATH_HZ` / `BREATH_NECK`
和 `STAND` 基准姿态都对着 `DuckMotion.kt` 抄，位置积分（平滑后速度 × dt，yaw 参与
x/z 的分解）和素材混合（`frameAt` 插值 + `clipWeight` 淡入淡出 + 跳过下标 9 的嘴）
对着 `sample()` 和 `GaitClip` 抄。

## 现在还没做

| | 状态 |
|---|---|
| 鸭子渲染 + 窗口 resize | ✅ |
| 播真机步态 + 键盘驱动 | ✅ 只有 `walk` 一段（0.4 m/s），转身/后退没有对应素材 |
| 机位切换（`window.duck.setView(0..7)`） | 只在 devtools 里手敲，没有 UI |
| 更多动作（跑 / 转身 / 前滚翻 / 踢球…） | ❌ 素材侧的事：`gaits.json` 现在的格式是"切一个周期循环播"，装得下周periodic 的步态，装不下前滚翻这种一次性动作，要另设格式 |
| 把它连上 agent（收语音 / TTS） | ❌ 下一步。`capabilities/default.json` 里的权限那时候要往上加 |
| Windows / Linux 构建 | 没试过，配置里没写死 macOS 的东西 |

桌面上没有触摸屏，"按住说话"那个交互要重新想一个（快捷键？点一下开始点一下停？）。
