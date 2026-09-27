// 规格 §6.5。关节位移的单位是米，鸭子大约 0.25 m 高。
//
// 这一版把"方块和球拼的关节鸭"换成了真机 microduck 的 STL 网格（规格 §6.1 原方案是方块鸭，
// 这里是有意偏离；§6.1 之所以否掉真机网格，是因为网格是 CC BY-SA-NC，理由见 README）。
// 网格和关节表都在 duck-meshes.js 里，由 tools/duckmesh/build.py 从 microduck_rl 生成。
//
// 和规格 §6.5 那段示例代码的差异，逐条记在这里：
//   1. 几何来自真机网格，不是 box()/sphere()；
//   2. 一条腿是 5 级铰链（髋转向→髋侧摆→髋前后→膝→踝），不是"一个 Group 三个欧拉角"。
//      真机三个髋轴不在同一点上（转向轴与侧摆轴相距 18 mm），合并会让侧摆绕错点转；
//   3. 右腿下标 11-14 **不取反**。规格那句"右腿在画面上取反"是给方块鸭写的
//      （方块鸭的左右腿是同一个构造函数镜像出来的）；真机左右腿各有一套带符号的舵机约定，
//      DuckMotion 的 STAND 直接喂进真机骨架就能让脚底落在 y=0（见 tools/duckmesh/README.md），
//      再取反反而会错。每个下标的正负号在 DUCK_MODEL.signs 里，是从真机轴线量出来的；
//   4. 鸭嘴（下标 9）在真机上是闭链连杆，MJCF 里没有这个自由度，枢轴是按嘴壳包围盒估的，
//      张嘴的幅度在 JAW_SCALE 打了个折（真机的下嘴壳很大，0.6 rad 全开像河马）；
//   5. 材质用的是真机 MJCF 里那套颜色（当 sRGB 用），外加一盏半球光、一盏轮廓光，
//      规格那两盏灯的强度也调亮了 —— 方块鸭时代的 0.7/0.8 在 r155+ 的物理光照下偏暗；
//   6. 加了"自动贴地"（见下面 groundShift）：规格把 root 定在地面上，模型内部再补一个
//      竖直偏移，坐下时躯干才会像真机那样降下来而不是整只鸭悬空；
//   7. 相机从规格那个固定斜机位（0.55, 0.32, 0.72）改成**正面机位 + 软跟随**。
//      原因：`velocity(0.2, 800ms)` 一步就是 16 cm，而画面在鸭子那一层只有约 27 cm 宽
//      —— 一步到边缘、两步出画，位置又只能靠速度积分，出画就回不来了（§11 手测 3 只能看一次）。
//      现在鸭子正对观众，可以先走出去 CAM_MAX_LEAD 那么远（看得见在走），停下后镜头平滑追回来。
//      另外加了 8 个机位（`window.duck.setView(0..7)`，绕一圈 45° 一格，0 = 正前方），
//      是调试面板拿来换角度看模型的，规格里没有。
const joints = new Array(15).fill(0);
const root = { x: 0, z: 0, yaw: 0 };

// 规格 §6.5 的 alpha: true，按规格保留。
// 实测记录：API 34 的 WebView 上 true/false 都能正常上屏（见 webgl-probe.html 左右两半）；
// 但在 API 28 那个旧 WebView 上，alpha:true 的 canvas 会被合成器整个丢弃、alpha:false 至少
// 能合成出一层（内容仍是黑的，因为那个 WebView 的 GL 共享纹理通路本身是坏的）。
// 结论：这是旧 WebView 的环境问题，不是这行代码的问题，不要为它改这里的参数。
const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setSize(window.innerWidth, window.innerHeight);
document.body.appendChild(renderer.domElement);

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(35, innerWidth / innerHeight, 0.01, 10);

// 正面机位 + 软跟随。相机只平移、不转向（朝向始终是正前方），所以鸭子转头/身体转向
// 仍然看得见；位置上是"鸭子先走出去一小段，镜头再追"。
const CAM_BACK = 0.9;        // 镜头离鸭子多远（米）
const CAM_Y = 0.34;          // 镜头高度
const CAM_LOOK_Y = 0.13;     // 视线落点高度（鸭子躯干）
const CAM_LAG = 0.06;        // 每帧追 6%：30 fps 下时间常数约 0.5 s
const CAM_MAX_LEAD = 0.05;   // 鸭子最多偏离画面中心多少米。硬上限，兜住"永不出画"
let camX = 0;
let camZ = 0;

// 8 个机位：绕鸭子一圈，45° 一格，0 = 正前方（默认，也是原来那个软跟随机位）、
// 2 = 鸭子左侧、4 = 正后方、6 = 鸭子右侧。镜头只平移不转向，所以每个机位都是"平视这个方位"，
// 软跟随照旧把鸭子按在画面中间。
let viewIndex = 0;

function updateCamera() {
  const dx = root.x - camX;
  const dz = root.z - camZ;
  const d = Math.hypot(dx, dz);
  if (d > 1e-6) {
    const step = d * CAM_LAG;
    camX += (dx / d) * step;
    camZ += (dz / d) * step;
  }
  const ox = root.x - camX;
  const oz = root.z - camZ;
  const od = Math.hypot(ox, oz);
  if (od > CAM_MAX_LEAD) {
    const pull = (od - CAM_MAX_LEAD) / od;
    camX += ox * pull;
    camZ += oz * pull;
  }
  const angle = viewIndex * Math.PI / 4;
  camera.position.set(
    camX + Math.sin(angle) * CAM_BACK,
    CAM_Y,
    camZ + Math.cos(angle) * CAM_BACK,
  );
  camera.lookAt(camX, CAM_LOOK_Y, camZ);
}

// 规格只写了环境光 + 一盏平行光。真机网格是浅色塑料壳，只有这两盏会显得很扁，
// 所以补了两盏（半球光当环境色，背后一盏当轮廓光），规格那两盏的值也调亮了一档。
scene.add(new THREE.AmbientLight(0xffffff, 0.85));
const sun = new THREE.DirectionalLight(0xffffff, 1.35);
sun.position.set(0.4, 1, 0.3);
scene.add(sun);
scene.add(new THREE.HemisphereLight(0xdCEBFF, 0x30281c, 0.75));
const rim = new THREE.DirectionalLight(0xfff2df, 0.7);
rim.position.set(-0.5, 0.6, -0.8);
scene.add(rim);

const duck = new THREE.Group();
scene.add(duck);

// ---------------------------------------------------------------------------
// 解码 duck-meshes.js。base64 走 <script> 标签，不用 fetch：
// WebView 里 file:// 的 fetch 会被 CORS 拦掉，而且同步解码能在第一帧就画出鸭子。
// ---------------------------------------------------------------------------
const MESH_DATA = window.DUCK_MESHES;
const MODEL = window.DUCK_MODEL;

const blob = (function () {
  const text = atob(MESH_DATA.data);
  const bytes = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
  return bytes;
})();

function pad4(n) { return (4 - (n % 4)) % 4; }

/** 归一化坐标 + int8 法线 + uint16/32 索引 → BufferGeometry。 */
function decodeGeometry(name) {
  const m = MESH_DATA.meshes[name];
  const posBytes = m.vcount * 6;
  const normAt = m.offset + posBytes + pad4(posBytes);
  const idxAt = normAt + m.vcount * 3 + pad4(m.vcount * 3);

  const position = new Float32Array(m.vcount * 3);
  const quant = new Uint16Array(blob.buffer, blob.byteOffset + m.offset, m.vcount * 3);
  for (let i = 0; i < position.length; i++) {
    const k = i % 3;
    position[i] = m.min[k] + (quant[i] / 65535) * (m.max[k] - m.min[k]);
  }
  const normal = new Float32Array(m.vcount * 3);
  const packed = new Int8Array(blob.buffer, blob.byteOffset + normAt, m.vcount * 3);
  for (let i = 0; i < normal.length; i++) normal[i] = packed[i] / 127;

  const index = m.i32
    ? new Uint32Array(blob.buffer, blob.byteOffset + idxAt, m.icount).slice()
    : new Uint16Array(blob.buffer, blob.byteOffset + idxAt, m.icount).slice();

  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.BufferAttribute(position, 3));
  geometry.setAttribute('normal', new THREE.BufferAttribute(normal, 3));
  geometry.setIndex(new THREE.BufferAttribute(index, 1));
  return geometry;
}

/** 一个颜色一份材质；真机是哑光注塑件，粗糙度按"壳 / 深色件 / 橙色软胶"分三档。 */
const materials = new Map();
function material(hex) {
  let m = materials.get(hex);
  if (m) return m;
  const c = new THREE.Color(hex);
  const dark = c.r * 0.299 + c.g * 0.587 + c.b * 0.114 < 0.35;
  m = new THREE.MeshStandardMaterial({
    color: hex,
    roughness: dark ? 0.55 : 0.42,
    metalness: dark ? 0.15 : 0.0,
  });
  materials.set(hex, m);
  return m;
}

const geometries = new Map();
function geometry(name) {
  let g = geometries.get(name);
  if (!g) {
    g = decodeGeometry(name);
    geometries.set(name, g);
  }
  return g;
}

// ---------------------------------------------------------------------------
// 从 DUCK_MODEL 摆出关节树。hinges 在生成时已经排成"父在子之前"。
// 每个铰链的局部坐标系都对齐屏幕轴（Y 上、+Z 朝前、+X 是鸭子的左侧），所以下标绕哪根轴
// 就是规格 §6.2 那一列，代码里不用再换算。
// ---------------------------------------------------------------------------
const nodes = { root: duck };
for (const h of MODEL.hinges) {
  const node = new THREE.Group();
  node.position.fromArray(h.pos);
  nodes[h.parent].add(node);
  nodes[h.name] = node;
  h.node = node;
}

for (const part of MODEL.parts) {
  const mesh = new THREE.Mesh(geometry(part.m), material(part.c));
  mesh.position.fromArray(part.p);
  mesh.quaternion.fromArray(part.q);
  nodes[part.h].add(mesh);
}

// 脚下那层接触阴影：一张用 canvas 画的径向渐变，比开 shadowMap 便宜得多
//（手机上 30 帧的预算很紧），也足够把鸭子"按"在地面上。
const shadow = (function () {
  const size = 128;
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext('2d');
  const g = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  g.addColorStop(0, 'rgba(0,0,0,0.55)');
  g.addColorStop(0.55, 'rgba(0,0,0,0.22)');
  g.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, size, size);
  const plane = new THREE.Mesh(
    new THREE.PlaneGeometry(0.26, 0.26),
    new THREE.MeshBasicMaterial({
      map: new THREE.CanvasTexture(canvas),
      transparent: true,
      depthWrite: false,
    }),
  );
  plane.rotation.x = -Math.PI / 2;
  plane.position.y = 0.0012;
  duck.add(plane);
  return plane;
})();

// 自动贴地。规格 §6.3 把 root 定义在地面上（duck.position.y 恒为 0），这里没有违反它：
// 模型内部再补一个竖直偏移，让**脚底最低点始终落在 y=0**。为什么需要：
// 贴地偏移只能按某一个姿态烘进几何（生成器按 STAND 算的），而真机的躯干高度是随姿态变的
// —— 坐下时真机躯干从 0.115 m 降到 0.060 m（microduck_rl 的 sit 关键帧），腿折起来、
// 脚还在原地。没有这个补偿，坐下来时整只鸭会悬空 8 cm。
// 代价：每帧变换 4 个脚部网格约 3400 个顶点，30 帧下可以忽略。
const groundProbe = (function () {
  const feet = ['left_ankle', 'right_ankle'].map((name) => {
    const parts = MODEL.parts.filter((p) => p.h === name);
    const verts = [];
    for (const part of parts) {
      const mesh = geometry(part.m).getAttribute('position');
      const v = new THREE.Vector3();
      for (let i = 0; i < mesh.count; i++) {
        v.fromBufferAttribute(mesh, i).applyQuaternion(
          new THREE.Quaternion().fromArray(part.q)).add(
          new THREE.Vector3().fromArray(part.p));
        verts.push(v.x, v.y, v.z);
      }
    }
    return { node: nodes[name], verts: new Float32Array(verts) };
  });
  return feet;
})();

function groundShift() {
  let low = Infinity;
  const v = new THREE.Vector3();
  for (const foot of groundProbe) {
    foot.node.updateWorldMatrix(true, false);
    const e = foot.node.matrixWorld.elements;
    for (let i = 0; i < foot.verts.length; i += 3) {
      const x = foot.verts[i], y = foot.verts[i + 1], z = foot.verts[i + 2];
      const wy = e[1] * x + e[5] * y + e[9] * z + e[13];
      if (wy < low) low = wy;
    }
  }
  return isFinite(low) ? -low : 0;
}

// 鸭嘴张开幅度打的折。DuckMotion 说话时把下标 9 推到 0.6 rad（34°），那是照方块鸭
// 那个小方嘴定的；真机的下嘴壳有 9 cm 宽，全开像河马张嘴。0.5 看着像在说话。
const JAW_SCALE = 0.5;

function apply() {
  duck.position.set(root.x, 0, root.z);
  duck.rotation.y = root.yaw;

  for (const h of MODEL.hinges) {
    if (h.index === null) continue;        // trunk_base：不打舵机
    const scale = h.name === 'jaw' ? JAW_SCALE : 1;
    h.node.rotation[h.axis] = MODEL.signs[h.index] * scale * joints[h.index];
  }

  duck.position.y = groundShift();
  shadow.position.y = 0.0012 - duck.position.y;   // 阴影永远贴在地面上
  updateCamera();

  renderer.render(scene, camera);
}

window.duck = {
  setFrame(next, x, z, yaw) {
    for (let i = 0; i < 15; i++) joints[i] = Number(next[i]) || 0;
    root.x = x;
    root.z = z;
    root.yaw = yaw;
    apply();
  },
  // 规格里没有这个函数：8 个机位，绕鸭子 45° 一格，0 是正前方。给调试面板换角度看模型用。
  setView(index) {
    viewIndex = (((Number(index) || 0) % 8) + 8) % 8;
    apply();
  },
  // 规格里没有这个函数：脚本解析时 WebView 可能还没布局完，innerWidth/innerHeight 会是 0，
  // 那样 canvas 就永远是 0×0、屏幕全黑。页面加载完成后由 DuckView 调一次。
  resize() {
    renderer.setSize(window.innerWidth, window.innerHeight);
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    apply();
  },
};

apply();
