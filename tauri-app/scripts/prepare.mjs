#!/usr/bin/env node
// 把桌面鸭子要的资源准备到 web/ 下。三件事都不是提交进版本库的东西，跑一次生成一次。
//
//   web/lib/     两个 WASM 运行时 + three.js，从 node_modules 拷
//   web/robot/   MJCF、网格、kinematics.json、microduck.glb，从 microduck_rl 生成
//   web/assets/  策略 ONNX，从 HuggingFace 下
//
// 用法：npm run setup
//
// 依赖：
//   - microduck_rl 检出在隔壁（默认 ../../microduck_rl，可用 MICRODUCK_RL 覆盖）
//   - 一个能 import mujoco 和 trimesh 的 Python（默认 /tmp/duckrl/bin/python，可用 DUCK_PY 覆盖）
//     没有的话：
//       uv venv /tmp/duckrl --python 3.12
//       uv pip install --python /tmp/duckrl/bin/python mujoco trimesh

import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const app = resolve(here, '..');            // tauri-app/
const repo = resolve(app, '..');            // duckApp/
const web = join(app, 'web');

const RL = process.env.MICRODUCK_RL ?? resolve(repo, '..', 'microduck_rl');
const PY = process.env.DUCK_PY ?? '/tmp/duckrl/bin/python';
const MODEL = join(RL, 'src/mjlab_microduck/robot/microduck');
const POLICY_URL = 'https://huggingface.co/pollen-robotics/microduck-policies/resolve/v5/velstand.onnx';

const step = (m) => console.log('\n=== ' + m + ' ===');

// 1. WASM 运行时 + three.js ---------------------------------------------------
step('拷运行时库 → web/lib/');
rmSync(join(web, 'lib'), { recursive: true, force: true });
mkdirSync(join(web, 'lib/three/addons'), { recursive: true });
mkdirSync(join(web, 'lib/three/utils'), { recursive: true });

const nm = join(app, 'node_modules');
const copy = (from, to) => {
  if (!existsSync(from)) throw new Error(`缺 ${from} —— 先 npm install`);
  cpSync(from, to);
};

// MuJoCo：ESM + 一个 10 MB 的 wasm
copy(join(nm, '@mujoco/mujoco/mujoco.js'), join(web, 'lib/mujoco.js'));
copy(join(nm, '@mujoco/mujoco/mujoco.wasm'), join(web, 'lib/mujoco.wasm'));

// onnxruntime-web：**用 wasm-only 那个构建**（ort.wasm.min.mjs）。
// 默认的 ort.min.mjs 带 WebGPU 后端，会先去要 jsep 变体；那个文件我们不放，
// 而 Tauri 的 dev server 对缺失文件回 index.html，报出来是
// "'text/html' is not a valid JavaScript MIME type" —— 完全看不出是文件不存在。
// 那堆 .mjs 变体各几十 KB，一并拷上省得再踩。
const ortDist = join(nm, 'onnxruntime-web/dist');
copy(join(ortDist, 'ort.wasm.min.mjs'), join(web, 'lib/ort.mjs'));
for (const f of ['ort-wasm-simd-threaded.mjs', 'ort-wasm-simd-threaded.jsep.mjs',
                 'ort-wasm-simd-threaded.jspi.mjs', 'ort-wasm-simd-threaded.asyncify.mjs']) {
  if (existsSync(join(ortDist, f))) copy(join(ortDist, f), join(web, 'lib', f));
}
copy(join(ortDist, 'ort-wasm-simd-threaded.wasm'), join(web, 'lib/ort-wasm-simd-threaded.wasm'));

// three：ESM 拆成了 three.module.js + three.core.js；GLTFLoader 还要 ../utils 下两个
copy(join(nm, 'three/build/three.module.js'), join(web, 'lib/three/three.module.js'));
copy(join(nm, 'three/build/three.core.js'), join(web, 'lib/three/three.core.js'));
copy(join(nm, 'three/examples/jsm/loaders/GLTFLoader.js'), join(web, 'lib/three/addons/GLTFLoader.js'));
for (const f of ['BufferGeometryUtils.js', 'SkeletonUtils.js']) {
  copy(join(nm, 'three/examples/jsm/utils', f), join(web, 'lib/three/utils', f));
}
console.log('  三个运行时都就位');

// 2. 模型资源 -----------------------------------------------------------------
step('拷 MJCF + 网格 → web/robot/');
if (!existsSync(MODEL)) throw new Error(`找不到模型：${MODEL}\n  用 MICRODUCK_RL 指到 microduck_rl 检出`);
rmSync(join(web, 'robot'), { recursive: true, force: true });
mkdirSync(join(web, 'robot/assets'), { recursive: true });
// scene.xml 和它 include 的 robot 文件放同级；meshdir="assets" 所以网格进 assets/
for (const f of ['scene.xml', 'robot_groundcontact.xml']) {
  copy(join(MODEL, f), join(web, 'robot', f));
}
// 物理那侧（MuJoCo 编译模型）要 STL，所以得带着
const xml = readFileSync(join(MODEL, 'robot_groundcontact.xml'), 'utf8');
const meshes = [...new Set([...xml.matchAll(/<mesh\s+file="([^"]+)"/g)].map(m => m[1]))];
for (const m of meshes) copy(join(MODEL, 'assets', m), join(web, 'robot/assets', m));
console.log(`  ${meshes.length} 个网格`);

step('生成 kinematics.json + microduck.glb');
execFileSync(PY, [join(repo, 'tools/mjcfkin/mjcf_to_kinematics.py'),
                  join(web, 'robot/scene.xml'), '-o', join(web, 'robot')],
             { stdio: 'inherit' });

// 3. 策略 ---------------------------------------------------------------------
step('下策略 → web/assets/');
mkdirSync(join(web, 'assets'), { recursive: true });
const onnx = join(web, 'assets/velstand.onnx');
if (existsSync(onnx)) {
  console.log('  已存在，跳过');
} else {
  // 用 curl 而不是 Node 的 fetch：fetch(undici) 不认 http_proxy 环境变量，
  // 在需要代理的网络上会直接 connect timeout；curl 认。
  execFileSync('curl', ['-sL', '--fail', '--max-time', '300', '-o', onnx, POLICY_URL],
               { stdio: ['ignore', 'ignore', 'inherit'] });
  console.log(`  velstand.onnx ${(statSync(onnx).size / 1024) | 0} KB`);
}

console.log('\n准备好了。npm run tauri dev 起窗口。\n');
