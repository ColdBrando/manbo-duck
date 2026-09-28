# duck 桌面壳（Tauri）

屏幕上的鸭子，在桌面上**实时跑真机策略**：MuJoCo 编译成 WebAssembly 算物理，
onnxruntime-web 跑 `velstand.onnx`，three.js 画。50 Hz 闭环，和真机 runtime 同一套约定。

它**不是**手机的移植版 —— 手机那套是"离线烘好关节角、前端放动画"，见下面「两条路」。

## 跑起来

```bash
npm install       # 三个依赖：@mujoco/mujoco / onnxruntime-web / three
npm run setup     # 把资源准备好（见下），一次就够
npm run dev       # 起窗口
npm run build     # 出 .app / .dmg，产物在 src-tauri/target/release/bundle/
```

`npm run setup` 做三件事，产物都在 `web/` 下且**不进版本库**：

| 产物 | 从哪来 | 为什么不提交 |
|---|---|---|
| `web/lib/` | 从 `node_modules` 拷（MuJoCo 10 MB + onnxruntime 14 MB 的 wasm + three） | 几十 MB，且 `npm install` 就有了 |
| `web/robot/` | 从 `microduck_rl` 拷 MJCF + 38 个 STL，再跑 `tools/mjcfkin/mjcf_to_kinematics.py` 生成 `kinematics.json` + `microduck.glb` | 能现场生成，提交进来就是一份会漂的副本 |
| `web/assets/` | 从 HuggingFace 下 `velstand.onnx`（775 KB） | 同上 |

两个前提：

- **`microduck_rl` 检出在隔壁**（默认 `../../microduck_rl`，用 `MICRODUCK_RL` 覆盖）
- **一个能 `import mujoco` 和 `trimesh` 的 Python**（默认 `/tmp/duckrl/bin/python`，用 `DUCK_PY` 覆盖）：
  ```bash
  uv venv /tmp/duckrl --python 3.12
  uv pip install --python /tmp/duckrl/bin/python mujoco trimesh
  ```

## 操作

| 操作 | 干什么 |
|---|---|
| **左键按住拖** | 转鸭子（左右拖 = 往左右转） |
| **右键按住拖** | 环绕镜头 |
| **滚轮** | 镜头推拉（0.25 ~ 2.5 m） |
| `空格` | 走 / 停 |
| `↑` `↓` | 加减前进速度 |

**左键转的是"指令"，不是直接改朝向。** 拖拽累积成一个偏航角速度指令（`cmd[2]`）交给策略，
松手后按 0.82/帧 衰减收住 —— 和游戏里鼠标转向的手感一致，但走的是真机的控制通路。

**站着转不了。** 这不是没做，是策略做不到：实测 `vx=0, wz=1.0` 时自相关峰值只有 0.088、
lag 11 之后全为 0（`turn10.csv`），也就是**原地转没有周期性步态**。真机同样如此。
走路时转是没问题的（`walkturn.csv`，vx=0.3/wz=1.0，自相关 0.966）。所以要用左键转，
得先让鸭子走着（默认就是走的）。

## 两条路

同一个"屏幕鸭子"，桌面和手机走的是**两条不同的路**：

| | 手机（`app/`） | 桌面（这里） |
|---|---|---|
| 动作怎么来 | `tools/duckgait/bake_gait.py` 离线录一段周期步态 → `gaits.json` → 放动画 | **策略实时推理**，50 Hz |
| 物理 | 没有 | **MuJoCo 在算** —— 会摔、会碰 |
| 能做什么 | 只有周期步态（走、转） | 全部技能（翻滚、踢球、坐站、轮滑…） |
| 资源 | 共用 `app/src/main/assets/duck/` | 自带一套 20 MB 模型 + 两个 WASM 运行时 |
| 前端 | `duck.js` | 这里的 `web/app.js` |

**为什么换**：烘步态那条路试过，卡在两点 —— 一是 `gaits.json` 那套格式只装得下周periodic
的步态，前滚翻/踢球这种一次性动作装不下；二是策略跟训练环境绑得很死，从 HuggingFace
下一个社区策略（`HannesVonEssen/microduck-running`）在官方 harness 里**全速度都跑不起来**，
只有官方那个 `velstand` 能用。实时跑策略没有这两个问题。

代价是桌面和手机不再共用前端 —— 这是换路线的固有成本，不是没做好。

## 结构

```
tauri-app/
  package.json          三个依赖，没有前端框架、没有打包器
  scripts/prepare.mjs   准备资源（上面那张表）
  tools/telemetry.py    排障用：页面把状态和画面 POST 出来
  web/                  ← frontendDist 指这里
    index.html          只有 import map 和一个 canvas
    app.js              全部逻辑：物理 / 策略 / 渲染 / 输入
    lib/ robot/ assets/ 生成物，gitignore
  src-tauri/
    src/lib.rs          只开窗口，没有别的逻辑
    tauri.conf.json     frontendDist: "../web"
```

## 关键约定（别改错）

全部对齐真机 runtime，改任何一处都会让鸭子的行为和真机不一致：

- **观测 61 维**：角速度(3) + 投影重力(3) + 关节位置(14) + 关节速度(14) + 上一步动作(14) + 指令(13)
  （3+3+14+14+14+13 = 61）。顺序和 `microduck_rl/scripts/infer_policy.py` 的 `get_observations` 一致。
- **动作 14 维**：是**位置目标相对 `DEFAULT_POSE` 的偏移**，直接写进 `data.ctrl`。
- **50 Hz 控制**：物理步长 0.005 s，每 4 步推理一次（`DECIMATION = 4`）。
- **坐标**：全程用 MJCF 的约定（z 朝上、米）。three.js 那边靠 `camera.up.set(0,0,1)` 适配，
  **不转场景也不转数据** —— 这样屏幕上的坐标和 MJCF 一一对应，对照调试少绕一层。
- **渲染用 MuJoCo 的世界变换**（`data.xpos` / `data.xquat`）直接摆每一块，不自己算正运动学。
  渲染和物理因此不可能漂。

## 踩过的坑

1. **GLB 的顶点必须取自 `model.mesh_vert`，不能读原始 STL。**
   MuJoCo 编译时会改写 STL 顶点（实测 `trunk_base.stl` 的 X/Z 被对调），而 `geom_pos/geom_quat`
   是编译后的值。拿编译后的变换配原始顶点 —— 每个零件的位置朝向都对、**自身形状却是错的**，
   表现是整只鸭子"散架"。而且逐项验证（body 位姿 ✓ geom 位置 ✓ geom 四元数 ✓ 包围盒 ✓）
   **全都能通过**，极难定位。见 `tools/mjcfkin/mjcf_to_kinematics.py` 顶部注释。

2. **颜色取 `mat_rgba`，不是 `geom_rgba`。** geom 挂了 `material="xxx"` 时，
   `geom_rgba` 只是占位（实测全是 0.5,0.5,0.5）。

3. **`GLTFLoader` 会改名。** 它过一遍 `PropertyBinding.sanitizeNodeName()`，把 `[ ] . : /`
   从名字里**删掉** —— `ankle_left.stl` 到了 JS 侧成了 `ankle_leftstl`。两边得用同一条
   规范化规则当键（`app.js` 里的 `san()`）。

4. **onnxruntime 要用 wasm-only 的构建**（`ort.wasm.min.mjs`）。默认那个带 WebGPU 后端，
   会先去要 `jsep` 变体；文件不存在时 **Tauri 的 dev server 回 `index.html`**，于是报
   `'text/html' is not a valid JavaScript MIME type` —— 从字面完全看不出是"文件不存在"。
   定位办法是逐个 `fetch` 比对 `content-type` 和**字节数**（对上 `index.html` 的大小就露馅了）。
   `executionProviders: ['wasm']` **拦不住**它。

5. **Tauri dev 会监视 `frontendDist` 目录**，往里面写任何文件都会触发页面重载。
   踩过：telemetry 日志写在 `web/` 里 → 每写一条日志就重载一次 → 无限循环 → 全黑跑不起来。
   所以 `tools/telemetry.py` 的日志落在 `/tmp`。

6. **不要用全屏 `screencapture` 看画面**，会抓进用户屏幕上正在做的别的事。
   走 `tools/telemetry.py`：页面把诊断和画面 POST 出来，窗口被遮住也拿得到。

## 排障开关

`web/app.js` 顶部三个常量，平时都是 false：

| 开关 | 干什么 |
|---|---|
| `STATIC` | 冻在 qpos0 不跑策略，用来和 MuJoCo 原生渲染逐帧对照 |
| `ONLY` | 只显示一个 body，用来把装配问题缩到单个 body 上 |
| `TELEMETRY` | 把状态和画面 POST 给 `tools/telemetry.py`（先跑起来再开） |

## License

代码从 `microduck_rl`（**Apache-2.0**）移植。

**没有**参考官方那个 HF Space（`pollen-robotics/microduck-simulator`）的应用代码 ——
它的 README 抄了几个数字（常量、观测布局），但那份仓库**没声明 license**（默认保留所有权利），
所以实现是照 Apache-2.0 的 `infer_policy.py` 和 MJCF 自己写的。

网格模型的许可见 `app/src/main/assets/duck/duck-meshes.LICENSE.txt`（CC BY-SA-NC）。

## 手机上能不能跑（2026-09-28 实测，未完成）

在 `duck34`（API 34 google_apis，arm64）模拟器上实测过一轮。用**执行进度标记**定位
（页面 `fetch('./_mark/<阶段>')`，请求会落在 HTTP 服务器日志里）——比截图可靠，
Android 的 `screencap` 读不到硬件合成层。

**已经证明能跑通的**（标记一个个走到）：

```
✓ mujoco-loaded       MuJoCo WASM 加载
✓ model-compiled      MJCF 编译完成（38 个网格进 VFS）
✓ onnx-session-ready  ONNX 会话建立
✓ three-imported / glb-loaded / rig-built
✓ before-tick         进渲染循环
```

跑起来时 HUD 读到 **60 fps**、鸭子在走（`x/y` 在变）、直立度 -0.998。
所以 WASM 物理 + 推理这一层在 Android WebView 上是通的，**不需要 SharedArrayBuffer**
（我们用单线程构建，因此不需要 COOP/COEP 响应头）。

**唯一没过的一关是 WebGL 上下文**。三点观察：

1. 一开始 `duck34` 的 `hw.gpu.enabled = no`，Chrome 的 GPU 进程在崩溃循环
   （`Reinitialized the GPU process after a crash`），页面连 CSS 都渲染不出来。
2. 打开 GPU（`hw.gpu.mode = host`，宿主是 Apple M4）冷启动后：
   - **WebGL2 可用**（`Android Emulator OpenGL ES Translator (Apple M4)`），
     **WebGL1 不可用**
   - 各种上下文属性组合都 OK，**连 three.js 的默认参数集也 OK**
   - 一个只 `import 'three'` + `new WebGLRenderer()` + 渲染一个方块的最小页面**完全正常**
   - 但我们的应用在建 renderer 时报 `Error creating WebGL context.`
3. 把 renderer 提到最前面建（在加载那 55 MB 之前）之后，进度**一路走到了渲染循环**，
   HUD 读到 60 fps。所以怀疑是**资源压力**（内存/GPU 资源）导致晚建上下文失败。

**但最后一次复验又失败了**，而且那次唯一的差别是打开了 `preserveDrawingBuffer`
（为了从画布读回像素验证 WebGL 真的在画）。所以这条还没定论。

**明天要做的**：

- [ ] 把 renderer 提前这个改动在**干净状态**下重跑三次，确认不是偶然
- [ ] 单独验 `preserveDrawingBuffer: true` 是不是会触发失败（它会让 Chrome 多留一份缓冲）
- [ ] 从画布读回像素，确认 WebGL **真的在画**（现在只有"HUD 在刷新"这一个证据，
      截图读不到硬件合成层）
- [ ] 上面都过了再上**真机**——模拟器的图形栈终究是转译层，帧率不代表真机

**如果真要在手机上落地，还有四件工程活**（不只是验证）：

1. **资源体积**：55 MB 要进 APK（10 MB mujoco.wasm + 14 MB ort wasm + 20 MB STL +
   7.4 MB GLB）。GLB 那 431k 三角面对低端机偏重
2. **内存**：2.5 GB 的模拟器跑到最后只剩 346 MB 空闲，这个 payload 不轻
3. **输入重做**：现在全是鼠标。触摸要重新映射（单指拖=转向、双指拖=镜头、捏合=远近）
4. **会取代 Kotlin 那套**：`DuckMotion.kt` / `Gait.kt` / 烘好的 `gaits.json` 整条路都不需要了 ——
   这是架构决定，不是加功能

## 现在还没做

| | 状态 |
|---|---|
| 站立 / 走路 | ✅ 与官方 harness 的实测速度一致（0.157 vs 0.17 m/s） |
| 渲染 | ✅ 装配正确、颜色正确、实时 |
| 画质（环境贴图 / 真阴影 / 色调映射） | ⚠️ 有基础阴影和 ACES，但材质还没有环境贴图 —— 塑料感不足 |
| 更多动作（跑 / 翻身 / 踢球…） | ❌ 换策略文件即可，`web/assets/` 里放哪个跑哪个 |
| 转身 / 后退指令 | ❌ `velstand` 不支持（原地转没有周期性，实测过），要另找策略 |
| 接语音（按住说话之类） | ❌ 桌面没有触摸屏，交互要重新想 |
