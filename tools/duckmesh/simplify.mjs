// 二级简化 + 量化打包。输入是 build.py 焊好点的原始网格，输出一个自描述的二进制块。
//
// 为什么不用 glTF：three.min.js 是 UMD 核心包，没有 GLTFLoader/STLLoader；而且 WebView 里
// file:// 的 fetch 会被 CORS 拦掉。所以走"base64 塞进 <script>"这条路，运行时只需要一个
// 几十行的解码器，没有任何异步加载，第一帧就有鸭子。
//
// 每个网格的块布局（都 4 字节对齐）：
//   [ uint16 × vcount × 3 归一化坐标 ] [ pad ] [ int8 × vcount × 3 法线 ] [ pad ]
//   [ uint16 或 uint32 × icount 索引 ] [ pad ]
import fs from 'node:fs';
import { MeshoptSimplifier } from 'meshoptimizer';

const CREASE_COS = Math.cos(35 * Math.PI / 180);   // 法线夹角超过 35° 就断开，保住硬边

await MeshoptSimplifier.ready;

const spec = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const outJson = process.argv[3];
const outBin = process.argv[4];

function readRaw(path) {
  const buf = fs.readFileSync(path);
  const vcount = buf.readUInt32LE(0);
  const icount = buf.readUInt32LE(4);
  const positions = new Float32Array(buf.buffer, buf.byteOffset + 8, vcount * 3);
  const indices = new Uint32Array(buf.buffer, buf.byteOffset + 8 + vcount * 12, icount);
  return { positions: positions.slice(), indices: indices.slice() };
}

function compact(positions, indices) {
  const remap = new Int32Array(positions.length / 3).fill(-1);
  let n = 0;
  for (let i = 0; i < indices.length; i++) {
    const v = indices[i];
    if (remap[v] < 0) remap[v] = n++;
    indices[i] = remap[v];
  }
  const out = new Float32Array(n * 3);
  for (let v = 0; v < remap.length; v++) {
    if (remap[v] < 0) continue;
    out[remap[v] * 3] = positions[v * 3];
    out[remap[v] * 3 + 1] = positions[v * 3 + 1];
    out[remap[v] * 3 + 2] = positions[v * 3 + 2];
  }
  return out;
}

// 面法线与"平滑法线"夹角超过阈值的角点，单独拆一个顶点出来用面法线。
// 壳体的圆角保持光滑，方盒子/散热片的硬边保住 —— 比 three 的 computeVertexNormals 好看。
function buildNormals(positions, indices) {
  const triCount = indices.length / 3;
  const faceN = new Float32Array(triCount * 3);
  const smooth = new Float32Array(positions.length);
  for (let t = 0; t < triCount; t++) {
    const a = indices[t * 3] * 3, b = indices[t * 3 + 1] * 3, c = indices[t * 3 + 2] * 3;
    const e1x = positions[b] - positions[a], e1y = positions[b + 1] - positions[a + 1], e1z = positions[b + 2] - positions[a + 2];
    const e2x = positions[c] - positions[a], e2y = positions[c + 1] - positions[a + 1], e2z = positions[c + 2] - positions[a + 2];
    const nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
    faceN[t * 3] = nx; faceN[t * 3 + 1] = ny; faceN[t * 3 + 2] = nz;   // 未归一化 = 面积加权
    for (const v of [a, b, c]) { smooth[v] += nx; smooth[v + 1] += ny; smooth[v + 2] += nz; }
  }
  const normals = [];
  const outPositions = [];                         // 拆硬边会新增顶点，位置要跟着复制一份
  const newIndices = new Uint32Array(indices.length);
  const splitOf = new Map();                       // "顶点:面" → 拆出来的新顶点号
  const push = (v, x, y, z) => {
    normals.push(x, y, z);
    outPositions.push(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]);
    return normals.length / 3 - 1;
  };
  const smoothIdx = new Int32Array(smooth.length / 3).fill(-1);
  const unit = (x, y, z) => { const l = Math.hypot(x, y, z) || 1; return [x / l, y / l, z / l]; };
  for (let t = 0; t < triCount; t++) {
    const fn = unit(faceN[t * 3], faceN[t * 3 + 1], faceN[t * 3 + 2]);
    for (let k = 0; k < 3; k++) {
      const v = indices[t * 3 + k];
      const sn = unit(smooth[v * 3], smooth[v * 3 + 1], smooth[v * 3 + 2]);
      const dot = fn[0] * sn[0] + fn[1] * sn[1] + fn[2] * sn[2];
      if (dot >= CREASE_COS) {
        if (smoothIdx[v] < 0) smoothIdx[v] = push(v, sn[0], sn[1], sn[2]);
        newIndices[t * 3 + k] = smoothIdx[v];
      } else {
        const key = v + ':' + t;
        let id = splitOf.get(key);
        if (id === undefined) { id = push(v, fn[0], fn[1], fn[2]); splitOf.set(key, id); }
        newIndices[t * 3 + k] = id;
      }
    }
  }
  return { positions: new Float32Array(outPositions), normals: new Float32Array(normals),
           indices: newIndices };
}

const meshes = {};
const blocks = [];
let offset = 0;
let totalTris = 0, totalVerts = 0, rawTris = 0;

const pad4 = (n) => (4 - (n % 4)) % 4;

for (const m of spec.meshes) {
  let { positions, indices } = readRaw(m.path);
  rawTris += indices.length / 3;

  if (m.ratio < 1 && indices.length / 3 > m.minTris) {
    const target = Math.max(24, Math.floor((indices.length / 3) * m.ratio) * 3);
    const [simplified] = MeshoptSimplifier.simplify(indices, positions, 3, target, m.error, ['LockBorder']);
    indices = new Uint32Array(simplified);
  }
  positions = compact(positions, indices);
  const built = buildNormals(positions, indices);
  positions = built.positions;                     // 已含拆硬边新增的顶点
  const normals = built.normals, ni = built.indices;

  // 归一化坐标：uint16 铺满包围盒
  let lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (let v = 0; v < positions.length; v += 3) {
    for (let k = 0; k < 3; k++) {
      lo[k] = Math.min(lo[k], positions[v + k]);
      hi[k] = Math.max(hi[k], positions[v + k]);
    }
  }
  const extent = [hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]].map((e) => (e > 0 ? e : 1));
  const vcount = positions.length / 3;
  const icount = ni.length;
  const i32 = vcount > 65535;
  const posBytes = vcount * 6, normBytes = vcount * 3;
  const idxBytes = icount * (i32 ? 4 : 2);
  const size = posBytes + pad4(posBytes) + normBytes + pad4(normBytes) + idxBytes + pad4(idxBytes);
  const block = Buffer.alloc(size);

  for (let v = 0; v < vcount; v++) {
    for (let k = 0; k < 3; k++) {
      const q = Math.round(((positions[v * 3 + k] - lo[k]) / extent[k]) * 65535);
      block.writeUInt16LE(Math.max(0, Math.min(65535, q)), (v * 3 + k) * 2);
    }
    for (let k = 0; k < 3; k++) {
      const n = Math.max(-127, Math.min(127, Math.round(normals[v * 3 + k] * 127)));
      block.writeInt8(n, posBytes + pad4(posBytes) + v * 3 + k);
    }
  }
  for (let i = 0; i < icount; i++) {
    const at = posBytes + pad4(posBytes) + normBytes + pad4(normBytes) + i * (i32 ? 4 : 2);
    if (i32) block.writeUInt32LE(ni[i], at); else block.writeUInt16LE(ni[i], at);
  }

  blocks.push(block);
  meshes[m.name] = {
    min: lo.map((x) => +x.toFixed(6)), max: hi.map((x) => +x.toFixed(6)),
    vcount, icount, i32: i32 ? 1 : 0, offset,
  };
  offset += size;
  totalTris += icount / 3;
  totalVerts += vcount;
}

fs.writeFileSync(outBin, Buffer.concat(blocks));
fs.writeFileSync(outJson, JSON.stringify(meshes));
console.log(`简化: ${rawTris} → ${totalTris} 三角形 (${(100 * totalTris / rawTris).toFixed(1)}%), ` +
            `顶点 ${totalVerts}, 二进制 ${(offset / 1048576).toFixed(2)} MiB`);
