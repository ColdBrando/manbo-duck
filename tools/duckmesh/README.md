# duckmesh —— 屏幕鸭子的网格生成管线

把 [pollen-robotics/microduck](https://github.com/pollen-robotics/microduck) 的真机模型
（`microduck_rl` 里的 MJCF + 43 个 STL）编译成 `app/src/main/assets/duck/duck-meshes.js`。
屏幕上的鸭子就是真机鸭子，不是方块拼的。

```bash
# 一次跑完：读 MJCF → 装配关节树 → meshoptimizer 简化 → 量化 → base64 内联
python3 tools/duckmesh/build.py            # 约 40 s，产物 2.7 MiB（二进制 2.0 MiB）

# 端到端校验：读**产物**、按 duck.js 的算法摆姿势，和 MuJoCo 前向运动学逐零件对比
python3 tools/duckmesh/verify.py           # STAND/SIT/WALK 三个姿势，容差 3 mm

# 渲一张姿态图看效果（headless Chrome，和 app 用的是同一份 duck.js）
tools/duckmesh/shot.sh /tmp/x.png "0,-0.0873,-0.4579,-0.0049,0.4530,0.3491,0.3491,0,0,0,0,0.0873,0.4579,0.0049,-0.4530"
```

## 为什么是这条路

* **不用 glTF**：`three.min.js` 是 r159 的 UMD 核心包，没有 `GLTFLoader`/`STLLoader`，
  自己写一个紧凑格式的解码器只有几十行。
* **不用 `fetch`**：WebView 里 `file://` 的 `fetch`/XHR 会被 CORS 拦掉。base64 走
  `<script>` 标签，同步解码，第一帧就有鸭子，也不会有"模型还没加载完"的中间态。
* **量化 + 简化**：386 k 三角形 → 103 k（26.6%），顶点位置 uint16（按各自包围盒归一化）、
  法线 int8、索引 uint16/uint32。硬边按 35° 拆点，所以方盒子保硬边、壳体保持光滑。

## 关节树怎么来的

真机 MJCF 的 body 链是 `yaw → roll → pitch → knee → ankle`，屏幕关节树照抄这条链，
而不是规格 §6.5 里"一条腿一个 Group、三个欧拉角写在一起"的写法：真机三个髋轴不在同一点上
（转向轴与侧摆轴相距 18 mm），合并成一个 Group 会让侧摆绕错点转。

坐标对应（纯旋转，**不镜像**）：

| 屏幕 | 真机 MJCF |
|---|---|
| +X（鸭子的左侧） | +y |
| +Y（上）  | +z |
| +Z（前）  | +x |

不镜像是有意的：镜像能让"下标 0-4 画在屏幕 -X"这条旧外观原样保留，但会把 vy 侧移时髋部的
倾倒方向翻过来。两条腿左右对称，画在哪一侧看不出来；倾倒方向看得出来。

## 符号怎么定的（这是最容易错的地方）

规格 §6.2 的下标 → 舵机号那张表是契约，但方块鸭那版代码里"右腿下标 11-14 取反"是给
方块鸭写的补丁（它的左右腿是同一个构造函数镜像出来的）。真机左右腿各有一套带符号的舵机
约定，所以：

* **不取反**。`DUCK_MODEL.signs` 是生成时从真机轴线量出来的（`assemble.joint_signs()`：
  关节轴在世界系里的方向 → 投影到屏幕轴 → 取符号），不是猜的。
* **验证**：把 `DuckMotion` 的 `STAND` 原样喂进真机骨架，脚底落在 **+2.8 mm**（就是站着）；
  把符号全取反，整只鸭浮空 **3.8 cm** —— 这一条就把 15 个符号里的大多数钉死了。
* **端到端**：`verify.py` 拿生成好的资产（含量化误差）复算 STAND/SIT/WALK，最大偏差
  **0.03 mm**。渲染图只能看出个大概，这个才能证明 JS 那头也对。

鸭嘴（下标 9）是唯一没有真机依据的：真机是**闭链连杆**，MJCF 里没有这个自由度。枢轴按嘴壳
包围盒估（后缘 4% / 上缘 25%），方向是"张开时嘴尖朝下"，`duck.js` 里还有 `JAW_SCALE = 0.5`
（真机下嘴壳 9 cm 宽，0.42 rad 全开像河马）。

## 贴地

生成时按 **STAND** 站姿算一次偏移，把脚底对齐到 y=0（-2.82 mm）。但真机的躯干高度随姿态变
（坐下时躯干从 0.115 m 降到 0.060 m），所以 `duck.js` 每帧再算一次"脚部网格最低点"，
把整只鸭抬到地面上（`groundShift()`，约 3400 个顶点/帧）。没有这个补偿，坐下时整只鸭悬空 8 cm。

## 授权（重要）

`microduck_rl/README.md` 写着：

> 3D model files are licensed under Creative Commons BY-SA-NC.

也就是 **署名 — 非商业性 — 相同方式共享**。本产物（`duck-meshes.js`）继承同样的许可，
声明随文件放在 `app/src/main/assets/duck/duck-meshes.LICENSE.txt`。

* 自己玩、内部测试：没问题。
* **对外分发 / 售卖 / 上架**：NC 这一条会挡住商用；SA 这一条要求衍生物也用同样的许可。
* 想商用又想要真机外观：得找 Pollen Robotics 要授权，或者退回程序化几何
  （`tools/duckmesh` 这套装配逻辑可以复用，只是把网格换成自己做的）。

## 踩过的坑（改这套管线之前先看）

1. **换基要整体共轭**。网格顶点在生成时就烘了 S（变成屏幕系），所以零件的世界变换是
   `S·W·Sᵀ`，只左乘 S 会得到"零件全散架"的画面 —— 渲染图上一眼能看出散架，但看不出
   是转错了哪一步，`verify.py` 那种逐零件对比才定位得到。
2. **拆硬边会新增顶点**，位置数组要跟着复制一份，否则索引会指到位置数组外面，
   WebGL 报 `Vertex buffer is not big enough for the draw call`（画面直接空）。
3. **铰链的静止姿态只能是纯平移**。曾经让铰链节点带上 MJCF body 的朝向，结果零件相对变换
   一乘就把 body 朝向抵消掉了（`inv(A·W)·A·W·G = G`），节点却是轴对齐的 —— 又是散架。
4. **生成的 JS 不是 JSON**：裸键名、单引号、注释、十六进制颜色、尾逗号都有。
   `verify.py` 里那段 `_js_object_to_json` 就是为读它写的。
5. **重复零件决定绘制量**：11 个轴承 × 每个 5 千三角形 = 5.8 万/帧，比整车壳体还多。
   所以重复 6 次以上的零件单独用 8% 的配额（`--ratio-many`）。

## 文件

| 文件 | 作用 |
|---|---|
| `mjcf.py` | 极小 MJCF 解析器 + 前向运动学（够 microduck 的 `robot_*.xml` 用） |
| `assemble.py` | 屏幕关节树、坐标换基、地面偏移、关节符号 |
| `simplify.mjs` | Node + meshoptimizer：二次误差简化、硬边拆分、法线、量化打包 |
| `build.py` | 驱动器：MJCF+STL → `duck-meshes.js`（+ 许可声明） |
| `verify.py` | 端到端校验（产物 ↔ MuJoCo 前向运动学） |
| `shot.sh` | headless Chrome 渲一张姿态图 |
| `node_modules/` | 只有 meshoptimizer，`npm install` 装的 |
