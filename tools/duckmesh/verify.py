#!/usr/bin/env python3
"""端到端校验：读**生成好的** duck-meshes.js，按 duck.js 的算法摆姿势，
和 microduck 的 MJCF 前向运动学对比每个零件的位置。

    python3 tools/duckmesh/verify.py [--src microduck_rl]

比的是真实产物（含量化、硬边拆分、四元数写出），所以能抓到"Python 里对、JS 里错"
这一类问题 —— 光看渲染图只能看出个大概。零件位置按包围盒中心比，容差 3 mm
（简化本身会挪动 1% 量级的顶点，那不是错）。
"""
import argparse
import base64
import json
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from assemble import SCREEN_ROT, SPEC_JOINTS, load, transform  # noqa: E402
from mjcf import Pose  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ASSET = os.path.join(ROOT, 'app/src/main/assets/duck/duck-meshes.js')
XML_REL = 'src/mjlab_microduck/robot/microduck/robot_walk.xml'
TOL_MM = 3.0

STAND = [0, -0.0873, -0.4579, -0.0049, 0.4530, 0.3491, 0.3491, 0, 0, 0,
         0, 0.0873, 0.4579, 0.0049, -0.4530]
SIT = [0, -0.0873, -1.20, 1.40, -0.15, 0.3491, 0.3491, 0, 0, 0,
       0, 0.0873, 1.20, -1.40, 0.15]
WALK = [0.05, -0.0873, -0.8579, -0.0049, 0.4230, 0.3491, 0.6491, 0.2, 0, 0.6,
        -0.05, 0.0873, -0.0579, -0.0049, -0.4830]


def _js_object_to_json(text):
    """生成的 JS 对象字面量 → JSON：去注释、给裸键加引号、去尾逗号。"""
    text = re.sub(r'//[^\n]*', '', text)
    text = re.sub(r"('[^']*')", lambda m: '"' + m.group(1)[1:-1] + '"', text)
    text = re.sub(r'([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:', r'\1"\2":', text)
    text = re.sub(r'0x([0-9a-fA-F]+)', lambda m: str(int(m.group(1), 16)), text)   # 颜色是十六进制
    return re.sub(r',(\s*[}\]])', r'\1', text)


def parse_asset(path):
    """从生成的 JS 里抠出 DUCK_MESHES / DUCK_MODEL（正则读片段，不执行 JS）。"""
    src = open(path).read()
    model = json.loads(_js_object_to_json(src.split('window.DUCK_MODEL = ', 1)[1].rstrip().rstrip(';')))
    head = src[src.index('  meshes: {') + len('  meshes: {'):src.index('  },\n  data: [')]
    meshes = json.loads(_js_object_to_json('{' + head + '}'))
    data = ''.join(re.findall(r"^\s*'([A-Za-z0-9+/=]+)',$", src, re.M))
    return meshes, model, base64.b64decode(data)


def decode_positions(blob, meta):
    qual = np.frombuffer(blob, dtype='<u2', count=meta['vcount'] * 3, offset=meta['offset'])
    lo, hi = np.array(meta['min']), np.array(meta['max'])
    return lo + (qual.reshape(-1, 3) / 65535.0) * (hi - lo)


def axis_angle(axis, angle):
    a = np.zeros(3)
    a['xyz'.index(axis)] = 1.0
    c, s = np.cos(angle), np.sin(angle)
    K = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    return np.eye(3) + s * K + (1 - c) * (K @ K)


def quat_to_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
    ])


def hinge_worlds(model, joints):
    """和 duck.js 的 apply() 同一套算法：逐级铰链，每级绕一根屏幕轴转。"""
    world = {'root': np.eye(4)}
    for h in model['hinges']:
        m = np.eye(4)
        m[:3, 3] = h['pos']
        if h['index'] is not None:
            rot = np.eye(4)
            rot[:3, :3] = axis_angle(h['axis'], model['signs'][h['index']] * joints[h['index']])
            m = m @ rot
        world[h['name']] = world[h['parent']] @ m
    return world


def bbox_center(pts):
    return (pts.min(0) + pts.max(0)) / 2.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', default=os.path.join(ROOT, 'microduck_rl'))
    ap.add_argument('--asset', default=ASSET)
    args = ap.parse_args()

    meshes, model, blob = parse_asset(args.asset)
    asm, _ = load(os.path.join(args.src, XML_REL))
    rows = asm.part_rows()
    assert len(rows) == len(model['parts']), "零件数对不上，重跑 build.py"
    decoded = {name: decode_positions(blob, meta) for name, meta in meshes.items()}
    print(f"资产：{len(meshes)} 网格 / {len(model['parts'])} 零件 / {len(model['hinges'])} 铰链")

    bad = 0
    for label, joints in (('STAND', STAND), ('SIT', SIT), ('WALK', WALK)):
        world = hinge_worlds(model, joints)
        angles = {SPEC_JOINTS[i]: joints[i] for i in range(15) if SPEC_JOINTS[i]}
        pose = Pose(asm.mjcf, angles)
        worst, worst_name = 0.0, ''
        for part, src in zip(model['parts'], rows):
            # 鸭嘴在真机上是闭链，MJCF 里没有这个自由度：嘴张开时参考值本身就"不对"，
            # 只能靠渲染图看方向（枢轴是估的，见 assemble.JAW_PIVOT）。
            if part['h'] == 'jaw' and joints[9] != 0:
                continue
            ref_world = pose.geom_world(src['body'], src['geom'])
            ref = transform(asm.tris_of(src['mesh']), ref_world) @ SCREEN_ROT.T + asm.offset
            local = np.eye(4)
            local[:3, :3] = quat_to_mat(part['q'])
            local[:3, 3] = part['p']
            got = world[part['h']] @ local
            pts = decoded[part['m']] @ got[:3, :3].T + got[:3, 3]
            err = np.linalg.norm(bbox_center(pts) - bbox_center(ref)) * 1000
            if err > worst:
                worst, worst_name = err, f"{part['h']}/{part['m']}"
        flag = '' if worst <= TOL_MM else '  ← 超差'
        if worst > TOL_MM:
            bad += 1
        print(f"  {label:5s} 最大位移 {worst:6.2f} mm  ({worst_name}){flag}")

    print('校验通过' if bad == 0 else f'{bad} 个姿势超差')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
