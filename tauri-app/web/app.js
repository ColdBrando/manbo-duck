// 桌面鸭子：MuJoCo(WASM) 算物理、ONNX 跑策略、three.js 画。
//
// 和手机那套（WebView + `duck.js` 播烘好的关节角）是**两条不同的路**：
//   - 手机：`tools/duckgait/bake_gait.py` 离线录一段周期步态 → `gaits.json` → 放动画
//   - 这里：策略**实时推理**，物理**真的在算** —— 所以能摔、能碰、能跑全部技能，
//     代价是桌面端自带一套 20 MB 的模型资源和两个 WASM 运行时，且和 Android 不共用前端。
//
// 观测向量 61 维、动作 14 维、控制 50 Hz —— 全部对齐真机 runtime，见下面每处的注释。

// ── 常量 ─────────────────────────────────────────────────────────────────────
// 关节顺序 == MJCF 里执行器的顺序 == 策略输出 14 维的顺序。
// 和屏幕鸭子（duck.js）那 15 个关节比，少了嘴（那边下标 9）：MJCF 里嘴不是独立关节。
const JOINT_NAMES = [
  'left_hip_yaw', 'left_hip_roll', 'left_hip_pitch', 'left_knee', 'left_ankle',
  'neck_pitch', 'head_pitch', 'head_yaw', 'head_roll',
  'right_hip_yaw', 'right_hip_roll', 'right_hip_pitch', 'right_knee', 'right_ankle',
];
// 静止站姿，和 MJCF 的 INIT 关键帧一致。策略输出的动作是**相对它**的偏移。
const DEFAULT_POSE = new Float32Array([
  0, -0.08726646259971647, -0.457924, -0.004940, 0.452984,
  0.3490658503988659, 0.3490658503988659, 0, 0,
  0, 0.08726646259971647, 0.457924, 0.004940, -0.452984,
]);
const NUM_JOINTS = 14;
const OBS_SIZE = 61;
const CMD_SIZE = 13;          // twist(3) + head(4) + body(6)
const TIMESTEP = 0.005;       // 物理 200 Hz
const DECIMATION = 4;         // 每 4 个物理步做一次推理 → 控制 50 Hz
const CTRL_DT = TIMESTEP * DECIMATION;

// 排障开关（平时都别开）：
//   STATIC     冻在 qpos0 不跑策略，用来和 MuJoCo 原生渲染逐帧对照
//   ONLY       只显示一个 body，用来把装配问题缩到单个 body 上
//   TELEMETRY  把状态 POST 给 tools/telemetry.py（先跑起来再开），
//              连画面一起传出来 —— 比 screencapture 干净：不抓用户屏幕上别的东西，
//              窗口被遮住也照样拿得到。服务器没起时 fetch 静默失败，不影响正常使用。
const STATIC = false;
const ONLY = '';
const TELEMETRY = false;

const hud = document.getElementById('hud');
const errEl = document.getElementById('err');
const fail = (m) => { errEl.textContent = String(m); console.error(m); };

const TEL = 'http://127.0.0.1:8791/';
const tel = (m) => { if (TELEMETRY) { try { fetch(TEL, { method: 'POST', mode: 'no-cors', body: String(m) }); } catch (_) {} } };
const telShot = (tag) => {
  if (!TELEMETRY) return;
  try {
    fetch(TEL + 'shot/' + tag, { method: 'POST', mode: 'no-cors',
      body: renderer.domElement.toDataURL('image/png') });
  } catch (_) {}
};

// ── WebGL 上下文：**在最前面就建** ───────────────────────────────────────────
// 为什么提前：实测在 Android 模拟器上，等把 55 MB 重资源（10 MB mujoco.wasm +
// 20 MB 网格进 VFS + 14 MB ort.wasm + 7.4 MB GLB／43 万三角面）都吃进内存之后
// 再建 WebGL 上下文，three.js 会报 `Error creating WebGL context.`；
// 把上下文提到最前面之后，同一台模拟器上执行进度能一路走到渲染循环。
// （还没在真机上复验，见 README 的待办。）
const THREE = await import('three');

// MJCF 是 z 朝上，three.js 默认 y 朝上。这里不动数据、也不转场景，直接把相机的
// up 设成 z —— 屏幕上的坐标和 MJCF 一一对应，对照调试时少绕一层。
// 宽高比可能是 0/0 = NaN（页面还没布局），syncSize() 会立刻纠正；先给 1 兜住
const camera = new THREE.PerspectiveCamera(38, innerWidth / innerHeight || 1, 0.01, 20);
camera.up.set(0, 0, 1);

// preserveDrawingBuffer：只有排障要读回画面时才开（不然缓冲已被清掉，采到永远是黑）。
// 平时关着，省一次拷贝。
const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: TELEMETRY });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
// 色调映射：三种都试过（同姿态同机位比过，见 README）。ACES 高光滚降最柔但橙带去饱和；
// AgX 最"电影感"但颜色最淡；Neutral 的颜色最接近真机参考图 —— 鸭子是产品渲染，
// 颜色读得准比电影感重要，所以选 Neutral。
renderer.toneMapping = THREE.NeutralToneMapping;
renderer.toneMappingExposure = 1.0;
document.body.appendChild(renderer.domElement);

// 尺寸：模块可能在**页面布局完成之前**就执行到这儿，那时 innerWidth/innerHeight 是 0，
// setSize 会把 canvas 定成 0×0 —— 而且之后不会自己恢复。表现极具迷惑性：
// **循环照跑、fps 正常、物理也正常，只是屏幕上什么都没有**。
// duck.js 里踩过同一个坑（那边靠 DuckView 在页面加载后补调一次 resize）。
// 这里每帧对一次尺寸，比只挂在 resize 事件上稳，代价可以忽略。
let lastW = -1, lastH = -1;
function syncSize() {
  if (innerWidth === lastW && innerHeight === lastH) return;
  lastW = innerWidth; lastH = innerHeight;
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
}
syncSize();

// ── 物理 ─────────────────────────────────────────────────────────────────────
const mj = await (await import('./lib/mujoco.js')).default();

// MJCF 和它的网格要装进虚拟文件系统：WASM 里没有真的文件系统，
// `<include>` 和 `<mesh file=...>` 都按名字在 VFS 里找。
const vfs = new mj.MjVFS();
const robotXml = await (await fetch('./robot/robot_groundcontact.xml')).text();
const meshFiles = [...new Set([...robotXml.matchAll(/<mesh\s+file="([^"]+)"/g)].map(m => m[1]))];
for (const f of meshFiles) {
  const r = await fetch('./robot/assets/' + f);
  if (!r.ok) throw new Error(`网格取不到: ${f} (${r.status})`);
  vfs.addBuffer('assets/' + f, new Uint8Array(await r.arrayBuffer()));
}
// scene.xml 用 <include> 引它；VFS 里按文件名找，所以名字要对上
vfs.addBuffer('robot_groundcontact.xml', new TextEncoder().encode(robotXml));

const model = mj.from_xml_string(await (await fetch('./robot/scene.xml')).text(), vfs);
const data = new mj.MjData(model);

// 地址表：观测里每一项从哪取
const adr = {
  qposAdr: JOINT_NAMES.map(n => model.jnt(n).qposadr),
  dofAdr: JOINT_NAMES.map(n => model.jnt(n).dofadr),
  gyroAdr: model.sensor('imu_ang_vel').adr,
  trunkId: mj.mj_name2id(model, mj.mjtObj.mjOBJ_BODY.value, 'trunk_base'),
};

// ── 策略 ─────────────────────────────────────────────────────────────────────
const ort = await import('./lib/ort.mjs');
// 显式指定只走 wasm 后端：默认会先试 webgpu(jsep)，那个变体我们不提供，
// 而 Tauri 的 dev server 对缺失文件回 index.html —— 报出来的是
// "'text/html' is not a valid JavaScript MIME type"，从字面完全看不出是文件不存在。
ort.env.wasm.wasmPaths = new URL('./lib/', import.meta.url).href;
ort.env.wasm.numThreads = 1;      // 单线程：不依赖 SharedArrayBuffer / 跨源隔离
const session = await ort.InferenceSession.create('./assets/velstand.onnx',
  { executionProviders: ['wasm'] });
const ortIn = session.inputNames[0], ortOut = session.outputNames[0];

const obs = new Float32Array(OBS_SIZE);
const lastAction = new Float32Array(NUM_JOINTS);
const cmd = new Float32Array(CMD_SIZE);     // 全零 = 站着别动
let uprightness = 0;                        // 末态拿它判直立（-1 是站直）

// 61 维观测，顺序和 infer_policy.py 的 get_observations 一致：
//   角速度(3) 投影重力(3) 关节位置(14) 关节速度(14) 上一步动作(14) 指令(13)
function buildObs() {
  const qp = data.qpos, qv = data.qvel, sens = data.sensordata;
  let i = 0;
  for (let a = 0; a < 3; a++) obs[i++] = sens[adr.gyroAdr + a];

  // 投影重力：把世界 -Z 用躯干四元数的**共轭**转进机身系（= 机身系里"下"的方向）。
  // 只用到旋转矩阵的第三列，所以不用把整个矩阵写出来。
  const xq = data.body(adr.trunkId).xquat;                 // [w,x,y,z]
  const w = xq[0], x = -xq[1], y = -xq[2], z = -xq[3];     // 共轭
  uprightness = -(1 - 2 * (x * x + y * y));
  obs[i++] = -(2 * (x * z + w * y));
  obs[i++] = -(2 * (y * z - w * x));
  obs[i++] = uprightness;

  for (let j = 0; j < NUM_JOINTS; j++) obs[i++] = qp[adr.qposAdr[j]] - DEFAULT_POSE[j];
  for (let j = 0; j < NUM_JOINTS; j++) obs[i++] = qv[adr.dofAdr[j]];
  for (let j = 0; j < NUM_JOINTS; j++) obs[i++] = lastAction[j];
  for (let j = 0; j < CMD_SIZE; j++) obs[i++] = cmd[j];
  return obs;
}

// 一个控制步：推理 → 写执行器 → 推 4 个物理步。
// 动作是**位置目标相对 DEFAULT_POSE 的偏移**，直接喂给 MJCF 里的 <position> 执行器。
async function controlStep() {
  const out = await session.run({ [ortIn]: new ort.Tensor('float32', buildObs(), [1, OBS_SIZE]) });
  const act = out[ortOut].data;
  for (let j = 0; j < NUM_JOINTS; j++) {
    if (!Number.isFinite(act[j])) return;     // 宁可这一拍不动，也别把 NaN 灌进物理
    lastAction[j] = act[j];
    data.ctrl[j] = DEFAULT_POSE[j] + act[j];
  }
  for (let s = 0; s < DECIMATION; s++) mj.mj_step(model, data);
}

// ── 渲染 ─────────────────────────────────────────────────────────────────────
// 用 MuJoCo 的**世界变换**直接摆每一块（body 的 xpos/xquat 就是世界位姿），
// 不自己做正运动学、也不按 kinematics 的父子关系建树。
// 这样渲染和物理不可能漂 —— 摆的就是 MuJoCo 刚算出来的那一份。
const { GLTFLoader } = await import('./lib/three/addons/GLTFLoader.js');

const kinematics = await (await fetch('./robot/kinematics.json')).json();
const gltf = await new GLTFLoader().loadAsync('./robot/microduck.glb');

// GLTFLoader 会过一遍 PropertyBinding.sanitizeNodeName()，它把 [ ] . : / 从名字里**删掉**，
// 于是 "ankle_left.stl" 到了这边就成了 "ankle_leftstl"。两边用同一条规则当键。
const san = (s) => String(s).replace(/\s/g, '_').replace(/[\[\]\.:\/]/g, '');

// 焊点 + 折边法线。
//
// STL 是"三角汤"：顶点不共享、也没有法线，three.js 只能按**面**算法线 —— 曲面上
// 就会看出一块块的平面、高光碎成一片片（放大头壳特别明显）。
// 先按位置焊点（1e-4 m），再按 36° 折边阈值重算法线：夹角小于它的相邻面算同一个
// 光滑面（圆角变光滑），大于它的保留硬边（棱线仍然是硬的）。官方模拟器也是这么做的。
//
// 必须先把 GLB 自带的 normal/uv 删掉 —— 否则焊点时"法线不同"会把该合并的顶点拦下来，
// 等于白焊。
const { mergeVertices } = await import('./lib/three/utils/BufferGeometryUtils.js');
const smoothCache = new Map();
function smoothGeometry(geo) {
  if (smoothCache.has(geo)) return smoothCache.get(geo);
  const g = geo.clone();
  g.deleteAttribute('normal');
  g.deleteAttribute('uv');
  const welded = mergeVertices(g, 1e-4);
  // 不按折边拆：toCreasedNormals 在这份网格上会出大片斑块（实测过，见 README）。
  // 直接全平滑，圆角变光滑、代价是细棱线也会被抹圆一点。
  welded.computeVertexNormals();
  g.dispose();
  smoothCache.set(geo, welded);
  return welded;
}

const tSmooth = performance.now();
const geomsByName = new Map();
gltf.scene.traverse(o => {
  if (!o.isMesh) return;
  const g = smoothGeometry(o.geometry);
  if (o.name) geomsByName.set(san(o.name), g);
  if (o.parent && o.parent.name) geomsByName.set(san(o.parent.name), g);
});
console.log('焊点+平滑法线：' + geomsByName.size + ' 个网格，' + (performance.now() - tSmooth).toFixed(0) + ' ms');

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x0b0f14);

// ── 环境光照 ─────────────────────────────────────────────────────────────────
// **这一块是"塑料感"的关键。** MeshStandardMaterial 的 roughness / metalness
// 全靠环境反射才有东西可反 —— 没有 scene.environment 时金属度几乎不起作用、
// 高光是死的，表现就是"发闷、发糊、像石膏"。
//
// 用 PMREM 卷一个**程序化的小房间**，不引外部 HDR 文件（省几 MB，参数也全在代码里）。
// 亮度按我们这套暗色调调：上前方一盏主光板、侧面一盏冷光、下后方一盏暖补光，
// 让壳体的高光和转角的轮廓都有东西可反。
function buildEnvMap(r) {
  const pmrem = new THREE.PMREMGenerator(r);
  const room = new THREE.Scene();
  const shellGeo = new THREE.BoxGeometry(10, 10, 10);
  const shellMat = new THREE.MeshBasicMaterial({ color: 0x1b2532, side: THREE.BackSide });
  room.add(new THREE.Mesh(shellGeo, shellMat));

  const disposables = [];
  const panel = (hex, intensity, w, h, pos) => {
    const g = new THREE.PlaneGeometry(w, h);
    const m = new THREE.MeshBasicMaterial({ color: new THREE.Color(hex).multiplyScalar(intensity) });
    const mesh = new THREE.Mesh(g, m);
    mesh.position.set(pos[0], pos[1], pos[2]);
    mesh.lookAt(0, 0, 0);
    room.add(mesh);
    disposables.push(g, m);
  };
  panel(0xffffff, 2.6, 3.0, 3.0, [ 0.8,  1.4,  1.8]);   // 主光：上前方
  panel(0xbfd8ff, 1.2, 4.5, 2.5, [-2.4,  0.8,  0.6]);   // 侧冷光
  panel(0xffd9b0, 0.9, 3.5, 2.5, [ 0.6, -2.6,  0.8]);   // 下后方暖补光

  const rt = pmrem.fromScene(room, 0.02);
  pmrem.dispose();
  shellGeo.dispose(); shellMat.dispose();
  disposables.forEach(d => d.dispose());
  return rt.texture;
}
scene.environment = buildEnvMap(renderer);
scene.environmentIntensity = 0.85;   // 再高就把暗色调冲淡了

// 环境贴图已经提供了大部分漫反射，半球光只留一点点免得暗部死黑
scene.add(new THREE.HemisphereLight(0xbcd4ff, 0x2a2620, 0.25));
const key = new THREE.DirectionalLight(0xffffff, 1.9);
key.position.set(0.6, 0.9, 1.4);
key.castShadow = true;
key.shadow.mapSize.set(1024, 1024);
const sc = key.shadow.camera;
sc.left = -0.6; sc.right = 0.6; sc.top = 0.6; sc.bottom = -0.6; sc.near = 0.1; sc.far = 4;
scene.add(key);
const rim = new THREE.DirectionalLight(0xffe9c9, 0.8);
rim.position.set(-0.8, -0.5, 0.7);
scene.add(rim);

// 地面：既让阴影落得下来，也接环境贴图的反射当"地台"。比鸭子大很多，走出去不掉出地面。
const floor = new THREE.Mesh(
  new THREE.PlaneGeometry(8, 8),
  new THREE.MeshStandardMaterial({ color: 0x0f151d, roughness: 0.85, metalness: 0.05 }));
floor.receiveShadow = true;
scene.add(floor);

// ── 材质分档 ─────────────────────────────────────────────────────────────────
// 按 MJCF 的**材质名**分，不按颜色猜 —— 名字很有语义（right_shell / xl330 /
// elec_rpi_robot_hat_pcb / soft_mouth_top），一眼就知道是什么件。
// 名字由 tools/mjcfkin/mjcf_to_kinematics.py 带出来（geom.mat）。
function materialFor(geom) {
  const c = geom.color || [1, 1, 1, 1];
  const n = (geom.mat || '').toLowerCase();
  let roughness = 0.5, metalness = 0.05;          // 默认：中灰尼龙结构件
  let shell = false;
  if (/shell|foot_|ankle_|sole_|jaw|noenoeil/.test(n)) {
    roughness = 0.28; metalness = 0.0; shell = true;   // 光面注塑外壳：高光要锐
  } else if (/soft|mouth/.test(n)) {
    roughness = 0.8; metalness = 0.0;             // 软胶：几乎不反光
  } else if (/pcb|elec_/.test(n)) {
    roughness = 0.62; metalness = 0.1;            // 电路板：哑光
  } else if (/trunk_base|yaw|bearing|motor_support|xl330|np_f970|neck_pitch|lens|speaker/.test(n)) {
    roughness = 0.4; metalness = 0.45;            // 深色结构件 / 舵机：带金属感
  }
  // 光面外壳用 MeshPhysicalMaterial 加一层清漆：真实的注塑亮件外面有一层透明漆，
  // 反射主要发生在漆面上（而不是底材），这是"像塑料"和"像石膏"的分界。
  if (shell) {
    return new THREE.MeshPhysicalMaterial({
      color: new THREE.Color(c[0], c[1], c[2]),
      roughness, metalness,
      clearcoat: 0.55, clearcoatRoughness: 0.12,
    });
  }
  return new THREE.MeshStandardMaterial({
    color: new THREE.Color(c[0], c[1], c[2]),
    roughness, metalness,
  });
}

// 每个 body 一个 Group，平铺挂在 scene 下；geom 按它在自己 body 系里的位姿挂进去。
const bodyGroups = new Map();
let meshCount = 0;
for (const b of kinematics.bodies) {
  const g = new THREE.Group();
  scene.add(g);
  bodyGroups.set(b.name, g);
  for (const geom of b.geoms) {
    const geo = geomsByName.get(san(geom.mesh));
    if (!geo) continue;
    const m = new THREE.Mesh(geo, materialFor(geom));
    m.castShadow = true; m.receiveShadow = true;
    m.position.fromArray(geom.pos);
    // MJCF 的四元数是 w 在前，three.js 是 (x,y,z,w)
    m.quaternion.set(geom.quat[1], geom.quat[2], geom.quat[3], geom.quat[0]);
    g.add(m);
    meshCount++;
  }
}
const bodyIds = new Map();
for (const b of kinematics.bodies) {
  bodyIds.set(b.name, mj.mj_name2id(model, mj.mjtObj.mjOBJ_BODY.value, b.name));
}
if (ONLY) for (const [name, g] of bodyGroups) g.visible = (name === ONLY);

function syncBodies() {
  for (const [name, g] of bodyGroups) {
    const bid = bodyIds.get(name);
    g.position.set(data.xpos[bid * 3], data.xpos[bid * 3 + 1], data.xpos[bid * 3 + 2]);
    g.quaternion.set(data.xquat[bid * 4 + 1], data.xquat[bid * 4 + 2],
                     data.xquat[bid * 4 + 3], data.xquat[bid * 4]);
  }
}

// 跟拍机位，用**球坐标**存 —— 这样右键才能环绕、滚轮才能推拉。
// 初始角度就是原来那个"侧后方"固定偏移 (-0.42, -0.50, 0.20) 换算来的：
// azim = atan2(-0.50, -0.42) = -130°，elev = asin(0.20/0.683) = 17°，dist = 0.683。
// 所以默认观感和改之前一模一样。
//
// 镜头只平移不转向（朝向始终对着鸭子），所以鸭子转头/转身都看得见。
let camAzim = -130 * Math.PI / 180;
let camElev = 17 * Math.PI / 180;
let camDist = 0.683;
const camFocus = new THREE.Vector3();
let camReady = false;

function followCam(instant) {
  const bx = data.xpos[adr.trunkId * 3], by = data.xpos[adr.trunkId * 3 + 1];
  const bz = data.xpos[adr.trunkId * 3 + 2];
  // 只有"焦点"平滑地追鸭子，角度是直接用的 —— 拖右键时手感要跟手，不能再插值
  if (!camReady || instant) { camFocus.set(bx, by, bz); camReady = true; }
  else camFocus.lerp(new THREE.Vector3(bx, by, bz), 0.12);

  const ce = Math.cos(camElev);
  camera.position.set(
    camFocus.x + camDist * ce * Math.cos(camAzim),
    camFocus.y + camDist * ce * Math.sin(camAzim),
    camFocus.z + camDist * Math.sin(camElev));
  camera.lookAt(camFocus.x, camFocus.y, camFocus.z - 0.02);
  key.target.position.copy(camFocus);
  key.target.updateMatrixWorld();
}

// ── 主循环 ───────────────────────────────────────────────────────────────────
// 按**实时**推进：仿真时间 1:1 跟墙钟。纯算力上能跑到 100 倍实时（10 秒仿真
// 0.1 秒跑完），但那是测试用的；看的时候得按实时，动作才自然。
let simT = 0, acc = 0, last = performance.now(), frames = 0, fpsT = last;
let nextShot = 3;      // 排障抓帧用：第 3 秒起每 6 秒传一张画面出去
const WALK_VX = 0.4;      // velstand 烘步态时用的同一个指令速度
cmd[0] = WALK_VX;

// STATIC 是画质调参用的**冻结模式**：姿态冻在 qpos0、指令清零，光照和取景才稳定。
// 光 reset 姿态不够 —— 控制循环还在跑，鸭子会带着 WALK_VX 走开，两次截图的鸭子位置
// 就不一样，而主光位置是固定的（只有 target 跟着走），光照角度跟着变，A/B 就没法比。
if (STATIC) {
  cmd.fill(0);                                   // 不跑
  mj.mj_resetData(model, data);
  mj.mj_forward(model, data);
  syncBodies(); followCam(true);                 // 相机立刻到位，别从原点飘过去
}

let busy = false;
async function tick() {
  requestAnimationFrame(tick);
  const now = performance.now();
  acc += Math.min(100, now - last) / 1000;   // 单帧 dt 掐在 100 ms 内，挂起回来别瞬移
  last = now;

  if (busy) return;                          // 推理是异步的，别重入
  applyYawInput();                           // 要在控制步之前：指令得进这一拍的观测
  let n = 0;
  while (acc >= CTRL_DT && n < 8) {
    busy = true;
    await controlStep();
    busy = false;
    acc -= CTRL_DT; simT += CTRL_DT; n++;
  }
  if (acc > CTRL_DT * 8) acc = 0;

  syncSize();                 // 页面布局完成之后才拿到真实尺寸，见上面的注释
  syncBodies();
  followCam(n === 0);
  renderer.render(scene, camera);

  frames++;
  if (now - fpsT > 500) {
    const fps = Math.round(frames * 1000 / (now - fpsT));
    const line =
      `仿真 ${simT.toFixed(1)}s   ${fps} fps\n` +
      `指令 vx=${cmd[0].toFixed(2)} wz=${cmd[2].toFixed(2)}   直立度 ${uprightness.toFixed(3)}\n` +
      `位置 x=${data.qpos[0].toFixed(3)} y=${data.qpos[1].toFixed(3)} z=${data.qpos[2].toFixed(3)}\n` +
      `左键拖=转鸭子   右键拖=转镜头   滚轮=远近   空格=走/停   ↑↓=速度`;
    hud.textContent = line;
    tel(`t=${simT.toFixed(1)}s fps=${fps} up=${uprightness.toFixed(3)} ` +
        `x=${data.qpos[0].toFixed(3)} y=${data.qpos[1].toFixed(3)}`);
    if (TELEMETRY && simT >= nextShot) { nextShot = simT + 6; telShot(`t${simT | 0}`); }
    frames = 0; fpsT = now;
  }
}

// 尺寸每帧已经对过了（syncSize），这里不用再挂 resize

// ── 输入 ─────────────────────────────────────────────────────────────────────
// 桌面没有触摸屏，手机那套"按住说话"在这儿没有对应物。这一版只做运动和视角。
addEventListener('keydown', (e) => {
  if (e.key === ' ')        { cmd[0] = cmd[0] === 0 ? WALK_VX : 0; e.preventDefault(); }
  if (e.key === 'ArrowUp')  { cmd[0] = Math.min(1.0, cmd[0] + 0.2); e.preventDefault(); }
  if (e.key === 'ArrowDown'){ cmd[0] = Math.max(0.0, cmd[0] - 0.2); e.preventDefault(); }
});

const canvas = renderer.domElement;
// 右键要用来环绕镜头，别弹系统菜单
canvas.addEventListener('contextmenu', (e) => e.preventDefault());

let dragBtn = -1;          // 0 = 左键（转鸭子），2 = 右键（转镜头）
let lastX = 0, lastY = 0;
let yawImpulse = 0;        // 左键拖拽累积的转向量，每帧衰减

canvas.addEventListener('mousedown', (e) => {
  if (e.button !== 0 && e.button !== 2) return;
  dragBtn = e.button; lastX = e.clientX; lastY = e.clientY;
  e.preventDefault();
});

addEventListener('mouseup', () => { dragBtn = -1; });

addEventListener('mousemove', (e) => {
  if (dragBtn < 0) return;
  const dx = e.clientX - lastX, dy = e.clientY - lastY;
  lastX = e.clientX; lastY = e.clientY;

  if (dragBtn === 2) {
    // 右键：环绕镜头。往右拖 = 镜头绕到鸭子右边，和大多数 3D 软件一致。
    camAzim -= dx * 0.008;
    camElev = Math.max(-1.3, Math.min(1.3, camElev + dy * 0.006));
  } else {
    // 左键：转鸭子。**不是直接改朝向**，而是转成偏航角速度指令交给策略
    // （cmd[2]）—— 真机就是这么转的，我们实测过 vx=0.3/wz=1.0 时策略能边走边转。
    // 累积 + 每帧衰减：拖着就转，手停下来就慢慢不转，像游戏里的鼠标转向。
    yawImpulse -= dx * 0.010;
  }
});

// 滚轮推拉镜头
canvas.addEventListener('wheel', (e) => {
  camDist = Math.max(0.25, Math.min(2.5, camDist * (1 + e.deltaY * 0.0012)));
  e.preventDefault();
}, { passive: false });

// 每帧把累积的拖拽量转成偏航指令。衰减系数决定"松手后还转多久"，
// 0.82 大约半秒收住。
function applyYawInput() {
  cmd[2] = Math.max(-1, Math.min(1, yawImpulse));
  yawImpulse *= 0.82;
  if (Math.abs(yawImpulse) < 1e-3) yawImpulse = 0;
}

errEl.textContent = '';
hud.textContent = `${kinematics.bodies.length} body / ${meshCount} mesh`;
tick();
