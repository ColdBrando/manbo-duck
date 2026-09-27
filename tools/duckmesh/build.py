#!/usr/bin/env python3
"""把 microduck 的真机模型编译成 app/src/main/assets/duck/duck-meshes.js。

    python3 tools/duckmesh/build.py [--src microduck_rl] [--ratio 0.25] [--error 0.01]

产物是**生成文件**，不要手改；模型换了重跑这条命令。
授权：microduck 的 3D 模型文件是 CC BY-SA-NC（见 microduck_rl/README.md），
      本产物因此继承同样的许可，随附 duck-meshes.LICENSE.txt。
"""
import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from assemble import SCREEN_ROT, load  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SRC = os.path.join(os.path.dirname(os.path.dirname(HERE)), 'microduck_rl')
XML_REL = 'src/mjlab_microduck/robot/microduck/robot_walk.xml'
OUT_JS = os.path.join(os.path.dirname(os.path.dirname(HERE)),
                      'app/src/main/assets/duck/duck-meshes.js')
WELD = 1e-5          # 10 µm：STL 没有共享顶点，焊接的容差


def weld(verts, tol=WELD):
    """按 tol 把重合顶点焊起来，位置取簇平均。"""
    key = np.round(verts / tol).astype(np.int64)
    _, inverse, counts = np.unique(key, axis=0, return_inverse=True, return_counts=True)
    inverse = inverse.reshape(-1)
    out = np.zeros((len(counts), 3))
    np.add.at(out, inverse, verts)
    out /= counts[:, None]
    return out


def to_raw(verts_mjcf):
    """STL 三角形 → 屏幕系焊接好的 (positions, indices)。"""
    v = verts_mjcf.reshape(-1, 3) @ SCREEN_ROT.T          # 屏幕系
    positions = weld(v)
    key = np.round(v / WELD).astype(np.int64)
    _, inverse = np.unique(key, axis=0, return_inverse=True)
    indices = inverse.reshape(-1).astype(np.uint32)
    tris = indices.reshape(-1, 3)
    keep = ((tris[:, 0] != tris[:, 1]) & (tris[:, 1] != tris[:, 2]) & (tris[:, 0] != tris[:, 2]))
    return positions.astype(np.float32), tris[keep].reshape(-1).astype(np.uint32)


def matrix_to_quat(m):
    """3x3 旋转 → three.js 的 (x, y, z, w)。"""
    t = np.trace(m)
    if t > 0:
        s = np.sqrt(t + 1.0) * 2
        w = 0.25 * s
        x = (m[2, 1] - m[1, 2]) / s
        y = (m[0, 2] - m[2, 0]) / s
        z = (m[1, 0] - m[0, 1]) / s
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = np.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        w = (m[2, 1] - m[1, 2]) / s
        x = 0.25 * s
        y = (m[0, 1] + m[1, 0]) / s
        z = (m[0, 2] + m[2, 0]) / s
    elif m[1, 1] > m[2, 2]:
        s = np.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        w = (m[0, 2] - m[2, 0]) / s
        x = (m[0, 1] + m[1, 0]) / s
        y = 0.25 * s
        z = (m[1, 2] + m[2, 1]) / s
    else:
        s = np.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        w = (m[1, 0] - m[0, 1]) / s
        x = (m[0, 2] + m[2, 0]) / s
        y = (m[1, 2] + m[2, 1]) / s
        z = 0.25 * s
    return [round(float(c), 6) for c in (x, y, z, w)]


def js_num(v, nd=6):
    return '[' + ','.join(f"{float(x):.{nd}g}" for x in v) + ']'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', default=DEFAULT_SRC, help='microduck_rl 仓库根目录')
    ap.add_argument('--ratio', type=float, default=0.25, help='目标三角形比例（只出现一两次的零件）')
    ap.add_argument('--ratio-many', type=float, default=0.08,
                    help='重复出现 6 次以上的零件（轴承、舵机）的比例：它们占了一多半的绘制量')
    ap.add_argument('--error', type=float, default=0.01, help='简化允许的相对误差')
    ap.add_argument('--min-tris', type=int, default=3000, help='小于这个数的小零件不简化')
    ap.add_argument('--out', default=OUT_JS)
    args = ap.parse_args()

    xml = os.path.join(args.src, XML_REL)
    asm, _cache = load(xml)
    tris_of = asm.tris_of
    print(f"读入 {os.path.relpath(xml, args.src)}：{len(asm.parts)} 个零件实例，"
          f"{len(set(p['mesh'] for p in asm.parts))} 个唯一网格")
    print(f"脚底偏移 {asm.offset[1] * 1000:+.2f} mm（STAND 站姿下最低点对齐 y=0）")
    print('关节符号（真机轴线相对屏幕轴的方向）:')
    for i, joint in enumerate(__import__('assemble').SPEC_JOINTS):
        hinge = __import__('assemble').SCREEN_OF_SPEC[i]
        print(f"  下标{i:2d} {str(joint):18s} → {hinge:18s} 绕 {asm.hinges[hinge]['axis']} 轴, "
              f"符号 {'+' if asm.signs[i] > 0 else '-'}{1}")

    tmp = tempfile.mkdtemp(prefix='duckmesh-')
    try:
        uses = {}
        for p in asm.parts:
            uses[p['mesh']] = uses.get(p['mesh'], 0) + 1
        drawn = {}
        spec = {'meshes': []}
        for name in sorted(set(p['mesh'] for p in asm.parts)):
            positions, indices = to_raw(tris_of(name))
            path = os.path.join(tmp, name + '.bin')
            with open(path, 'wb') as f:
                f.write(np.uint32(len(positions)).tobytes())
                f.write(np.uint32(len(indices)).tobytes())
                f.write(positions.tobytes())
                f.write(indices.tobytes())
            n = uses[name]
            ratio = args.ratio_many if n >= 6 else (args.ratio * 0.7 if n >= 3 else args.ratio)
            spec['meshes'].append({'name': name, 'path': path,
                                   'ratio': ratio, 'error': args.error,
                                   'minTris': 400 if n >= 3 else args.min_tris})
        spec_path = os.path.join(tmp, 'spec.json')
        with open(spec_path, 'w') as f:
            json.dump(spec, f)
        out_json, out_bin = os.path.join(tmp, 'meshes.json'), os.path.join(tmp, 'meshes.bin')
        subprocess.run(['node', os.path.join(HERE, 'simplify.mjs'), spec_path, out_json, out_bin],
                       check=True, cwd=HERE)

        meta = json.load(open(out_json))
        blob = open(out_bin, 'rb').read()
        emit(args.out, meta, blob, asm)
        drawn = sum(uses[n] * m['icount'] // 3 for n, m in meta.items())
        print(f"绘制量（含重复实例）{drawn} 三角形/帧，最大的一份是 "
              f"{max(((uses[n] * m['icount'] // 3, n) for n, m in meta.items()))[1]}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print(f"写出 {os.path.relpath(args.out, os.path.dirname(os.path.dirname(HERE)))} "
          f"({os.path.getsize(args.out) / 1048576:.2f} MiB)")


def emit(path, meta, blob, asm):
    b64 = base64.b64encode(blob).decode('ascii')
    chunks = [b64[i:i + 160000] for i in range(0, len(b64), 160000)]
    hinges = [h for h in asm.hinges.items()]
    parts = asm.part_rows()

    lines = [
        '// 由 tools/duckmesh/build.py 生成，不要手改。',
        '// 网格来自 pollen-robotics/microduck 的 STL（CC BY-SA-NC，见 duck-meshes.LICENSE.txt）。',
        '// 坐标已经是屏幕系（Y 上、面朝 +Z），每个铰链的局部坐标系对齐屏幕轴。',
        'window.DUCK_MESHES = {',
        '  meshes: {',
    ]
    for name, m in meta.items():
        lines.append(f"    '{name}': {{ min: {js_num(m['min'])}, max: {js_num(m['max'])}, "
                     f"vcount: {m['vcount']}, icount: {m['icount']}, i32: {m['i32']}, "
                     f"offset: {m['offset']} }},")
    lines += ['  },', '  data: [']
    lines += [f"    '{c}'," for c in chunks]
    lines += ['  ].join(""),', '};', '']
    lines += ['// 关节表：名字、父节点、在父坐标系里的位置、绕哪根屏幕轴、规格 §6.2 的下标。',
              'window.DUCK_MODEL = {',
              '  hinges: [']
    for name, info in hinges:
        if info['parent'] is None:
            continue
        axis = f"'{info['axis']}'" if info['axis'] else 'null'
        index = info['index'] if info['index'] is not None else 'null'
        lines.append(f"    {{ name: '{name}', parent: '{info['parent']}', "
                     f"pos: {js_num(info['pos'])}, axis: {axis}, index: {index} }},")
    lines += ['  ],', '  // 每个零件的铰链、网格名、颜色(sRGB 0-1)、相对铰链的位置和四元数。',
              '  parts: [']
    for p in parts:
        rel = p['rel']
        quat = matrix_to_quat(rel[:3, :3])
        rgb = [int(round(min(1.0, max(0.0, c)) * 255)) for c in p['rgba']]   # MJCF 的 rgba 当 sRGB 用
        hex_color = '0x%02x%02x%02x' % tuple(rgb)
        lines.append(f"    {{ h: '{p['hinge']}', m: '{p['mesh']}', "
                     f"c: {hex_color}, p: {js_num(rel[:3, 3])}, q: {js_num(quat)} }},")
    signs = ','.join(str(asm.signs[i]) for i in range(15))
    lines += ['  ],',
              f'  // duck.js 里每个下标乘的符号：真机轴线的正向和屏幕轴同向为 +1。',
              f'  signs: [{signs}],',
              '  offset: ' + js_num(asm.offset) + ',',
              '};', '']
    with open(path, 'w') as f:
        f.write('\n'.join(lines))

    lic = os.path.join(os.path.dirname(path), 'duck-meshes.LICENSE.txt')
    with open(lic, 'w') as f:
        f.write(LICENSE)


LICENSE = """duck-meshes.js 里的 3D 网格来自 pollen-robotics/microduck 的 STL 模型文件，
经 tools/duckmesh/build.py 简化、量化后内联。

上游声明（microduck_rl/README.md）：
    This project is licensed under the Apache 2.0 License.
    3D model files are licensed under Creative Commons BY-SA-NC.

即网格部分为 CC BY-SA-NC 4.0（署名—相同方式共享—非商业性使用）：
  * 署名：Pollen Robotics / microduck 项目
  * 非商业性使用：不得用于商业用途
  * 相同方式共享：本文件的衍生物必须以同样的许可分发

上游仓库：https://github.com/pollen-robotics/microduck
本仓库其余代码（duck.js / Kotlin / 工具脚本）不受此许可约束。
"""


if __name__ == '__main__':
    main()
