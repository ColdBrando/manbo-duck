#!/usr/bin/env python3
"""从 MJCF 生成屏幕鸭子用的骨架描述 + 合并网格。

为什么要有这一步：MuJoCo 和 three.js 得看**同一棵树**。
- MuJoCo 读 MJCF，算出 14 个关节角（qpos）
- three.js 照着同一棵树摆网格，把 qpos 填进去

如果两边各拿一份（现在 Android 那份是 `tools/duckmesh/build.py` 另外生成的
`duck-meshes.js`），MJCF 一改就漂了。

产物：
  kinematics.json   每个 body 的父子关系、静止位姿、它挂的 geom（网格名 + 变换 + 颜色）
  microduck.glb     全部用到的网格合并成一个，网格名 == STL 文件名（供 three.js 按名取用）

坐标：**原样输出 MJCF 的约定**（z 朝上、单位米），转换交给渲染侧做。
在这里翻转坐标的话，以后对照 MJCF 调试就得多绕一层。

## 两个踩过的坑（改之前先看）

1. **GLB 的顶点必须取自 `model.mesh_vert`，不能读原始 STL 文件。**
   MuJoCo 编译时会对 STL 顶点做自己的处理 —— 实测 `trunk_base.stl` 的 X/Z 被对调了。
   而 `geom_pos/geom_quat` 又是编译后的值；拿编译后的变换去配原始顶点，每个零件的
   位置和朝向都对、**自身形状却是错的**。表现是整只鸭子"散架"，而且逐项验证
   （body 位姿 ✓ geom 位置 ✓ geom 四元数 ✓ 包围盒 ✓）全都能通过，极难定位。
   用 `mesh_vert` 还顺带保证了**渲染几何 == 物理几何**，也不再需要 trimesh 读 STL。

2. **颜色要取 `mat_rgba`，不是 `geom_rgba`。**
   MJCF 里 geom 挂了 `material="xxx_material"` 时，`geom_rgba` 只是个占位
   （实测全是 0.5,0.5,0.5），真颜色在材质表里。

## 用法

    uv venv /tmp/duckrl --python 3.12
    uv pip install --python /tmp/duckrl/bin/python mujoco trimesh
    /tmp/duckrl/bin/python tools/mjcfkin/mjcf_to_kinematics.py \
        ../microduck_rl/src/mjlab_microduck/robot/microduck/scene.xml \
        -o ../tauri-app/web/robot

（`tauri-app/scripts/prepare.mjs` 把这条包了一层，平时不用手敲。）
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import mujoco
import numpy as np


def body_geoms(model: mujoco.MjModel, bid: int) -> list[dict]:
    """这个 body 底下所有 mesh 类型的 geom。"""
    out = []
    adr, num = model.body_geomadr[bid], model.body_geomnum[bid]
    for g in range(adr, adr + num):
        if model.geom_type[g] != mujoco.mjtGeom.mjGEOM_MESH:
            continue                      # 碰撞用的图元（盒/球/胶囊）不参与渲染
        mid = model.geom_dataid[g]
        mesh_file = mujoco.mj_id2name(model, mujoco.mjtObj.mjOBJ_MESH, mid)
        if not mesh_file:
            continue
        # 颜色：**有材质就用材质的**。MJCF 里 geom 挂 material="xxx_material" 时，
        # geom_rgba 只是个占位（实测全是 0.5,0.5,0.5），真颜色在 mat_rgba 里。
        matid = model.geom_matid[g]
        rgba = model.mat_rgba[matid] if matid >= 0 else model.geom_rgba[g]
        out.append({
            "type": "mesh",
            "mesh": mesh_file + ".stl",   # MJCF 里网格名不带扩展名，渲染侧要文件名
            "pos": [round(float(v), 7) for v in model.geom_pos[g]],
            "quat": [round(float(v), 7) for v in model.geom_quat[g]],
            "color": [round(float(v), 6) for v in rgba],
        })
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("xml", type=Path, help="场景 MJCF（含 include 的那份，如 scene.xml）")
    ap.add_argument("-o", "--out", type=Path, required=True, help="输出目录")
    ap.add_argument("--meshdir", type=Path, default=None,
                    help="网格目录，默认取 XML 同级的 assets/")
    ap.add_argument("--no-glb", action="store_true", help="只出 kinematics.json，不合并 GLB")
    args = ap.parse_args()

    meshdir = args.meshdir or (args.xml.parent / "assets")
    model = mujoco.MjModel.from_xml_path(str(args.xml))

    # ---- body 树 -----------------------------------------------------------
    bodies = []
    for bid in range(model.nbody):
        name = mujoco.mj_id2name(model, mujoco.mjtObj.mjOBJ_BODY, bid)
        if bid == 0:
            continue                      # world，渲染侧不需要
        parent = model.body_parentid[bid]
        bodies.append({
            "name": name,
            "parent": None if parent == 0 else
                      mujoco.mj_id2name(model, mujoco.mjtObj.mjOBJ_BODY, parent),
            "pos": [round(float(v), 7) for v in model.body_pos[bid]],
            "quat": [round(float(v), 7) for v in model.body_quat[bid]],
            "geoms": body_geoms(model, bid),
        })

    # ---- 执行器顺序的关节名 -------------------------------------------------
    # 这个顺序就是策略输出 14 维向量的顺序，必须和 MuJoCo 的 ctrl 下标一致
    actuated = []
    for a in range(model.nu):
        jid = model.actuator_trnid[a][0]
        actuated.append(mujoco.mj_id2name(model, mujoco.mjtObj.mjOBJ_JOINT, jid))

    # ---- 一个关节挂在哪根轴上（渲染侧要按轴转）-------------------------------
    joints = {}
    for jid in range(model.njnt):
        jname = mujoco.mj_id2name(model, mujoco.mjtObj.mjOBJ_JOINT, jid)
        if not jname or model.jnt_type[jid] == mujoco.mjtJoint.mjJNT_FREE:
            continue
        jb = model.jnt_bodyid[jid]
        joints[jname] = {
            "body": mujoco.mj_id2name(model, mujoco.mjtObj.mjOBJ_BODY, jb),
            # 轴在**父** body 坐标系里；渲染侧建立 hinge 节点时用它
            "axis": [round(float(v), 6) for v in model.jnt_axis[jid]],
            "pos": [round(float(v), 7) for v in model.jnt_pos[jid]],
        }

    args.out.mkdir(parents=True, exist_ok=True)
    kin = {
        "format": 1,
        "source": "从 MJCF 生成（mjcf_to_kinematics.py）",
        "note": "坐标是 MJCF 原样：z 朝上、单位米。渲染侧负责转换。",
        "mesh_dir": "./meshes",
        "bodies": bodies,
        "actuated_joints": actuated,
        "joints": joints,
    }
    kinpath = args.out / "kinematics.json"
    kinpath.write_text(json.dumps(kin, ensure_ascii=False, indent=1) + "\n")
    print(f"写出 {kinpath}（{len(bodies)} 个 body，{len(actuated)} 个执行关节，"
          f"{sum(len(b['geoms']) for b in bodies)} 个 mesh geom）")

    if args.no_glb:
        return 0

    # ---- 合并 GLB ----------------------------------------------------------
    #
    # **顶点取自 MuJoCo 编译后的 model.mesh_vert，不是原始 STL 文件。**
    # 这是个踩过的坑：MuJoCo 编译时会对 STL 顶点做自己的处理（实测 trunk_base.stl
    # 的 X/Z 被对调），而 geom_pos/geom_quat 又是编译后的值。拿编译后的变换去配
    # 原始顶点，每个零件的位置朝向都对、自身形状却是错的 —— 表现为整只鸭子"散架"，
    # 而且每项数值验证都能通过，极难定位。
    #
    # 用 model.mesh_vert 还顺带保证了一件事：**渲染的几何 == 物理的几何**，
    # 不可能再漂。也就不再需要 STL 文件和 trimesh 了。
    import trimesh

    used = []
    seen = set()
    for b in bodies:
        for g in b["geoms"]:
            if g["mesh"] not in seen:
                seen.add(g["mesh"])
                used.append(g["mesh"])

    scene = trimesh.Scene()
    total_faces = 0
    for fname in used:                      # fname 形如 "trunk_base.stl"
        # kinematics 里存的是文件名，MuJoCo 的网格名是不带扩展名的那个
        mname = fname[:-4] if fname.endswith(".stl") else fname
        mid = mujoco.mj_name2id(model, mujoco.mjtObj.mjOBJ_MESH, mname)
        if mid < 0:
            print(f"  !! 模型里没有网格 {mname}", file=sys.stderr)
            continue
        va, vn = model.mesh_vertadr[mid], model.mesh_vertnum[mid]
        fa, fn = model.mesh_faceadr[mid], model.mesh_facenum[mid]
        if vn == 0 or fn == 0:
            print(f"  !! {mname} 没有顶点/面", file=sys.stderr)
            continue
        verts = np.asarray(model.mesh_vert[va:va + vn], dtype=np.float64).reshape(-1, 3)
        faces = np.asarray(model.mesh_face[fa:fa + fn], dtype=np.int64).reshape(-1, 3)
        total_faces += len(faces)
        scene.add_geometry(trimesh.Trimesh(vertices=verts, faces=faces, process=False),
                           node_name=fname, geom_name=fname)

    glbpath = args.out / "microduck.glb"
    scene.export(str(glbpath))
    print(f"写出 {glbpath}（{len(used)} 个网格，{total_faces} 个三角面，"
          f"{glbpath.stat().st_size/1048576:.1f} MB）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
