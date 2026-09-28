#!/usr/bin/env python3
"""把真机策略录出来的轨迹烘成 app 能播的动作片段。

输入是 `microduck_rl/scripts/infer_policy.py --save-csv` 录的 CSV（每帧 61 维观测），
输出是 `app/src/main/assets/duck/gaits.json` —— 屏幕鸭子照着它走，
**走的就真的是训出来的那个步态**，不是我手调的正弦。

## 怎么录（在 microduck_rl 里，macOS）

```bash
# 1) 一个够用的环境（不需要 mjlab/torch/CUDA）
uv venv /tmp/duckrl --python 3.12
uv pip install --python /tmp/duckrl/bin/python mujoco onnxruntime
# BAM 不在 PyPI，要 uv.lock 里钉的那个 commit
uv pip install --python /tmp/duckrl/bin/python \
    "git+https://github.com/Rhoban/bam.git@62bd8ce12154340be97e06f7f41a0ca8f116d967"

# 2) 策略（HuggingFace）
python3 -c "from huggingface_hub import hf_hub_download as d; \
  print(d('pollen-robotics/microduck-policies','velstand.onnx',revision='v5'))"

# 3) 录。macOS 上 launch_passive 要 mjpython，无人值守跑不了；见 README 里的 headless 补丁。
#    注意 --new-cmd-obs（v5 策略是 61 维观测），以及**低于 0.3 m/s 它选择站着**。
/tmp/duckrl/bin/python infer_headless.py --walking velstand.onnx --new-cmd-obs \
    --lin-vel-x 0.4 --save-csv w40.csv

# 4) 烘
python3 tools/duckgait/bake_gait.py w40.csv --out app/src/main/assets/duck/gaits.json
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np

# 观测里的 14 个关节角相对 home 的偏移在 obs[6..20]（不含嘴）
OBS_JOINT_BASE = 6
N_JOINTS = 14

# duckApp 的 15 个关节下标的顺序：左腿 0-4、颈头 5-8、嘴 9、右腿 10-14。
# 策略的 14 个（去嘴）映射过去时，下标 9 之后要整体后移一位。
def joint_index(k: int) -> int:
    return k if k < 9 else k + 1


def load_offsets(path: Path) -> tuple[np.ndarray, np.ndarray]:
    """CSV → (时间, 每帧 14 个关节相对 home 的偏移)。"""
    rows = list(csv.DictReader(path.open()))
    if not rows:
        sys.exit(f"{path}: 空的")
    t = np.array([float(r["time"]) for r in rows])
    j = np.array([[float(r[f"obs_{OBS_JOINT_BASE + k}"]) for k in range(N_JOINTS)] for r in rows])
    return t, j


def cycle_length(j: np.ndarray) -> int:
    """自相关找周期（帧）。步态是周期的，取相关性最高的 lag。"""
    k = j[:, 3] - j[:, 3].mean()          # 左膝最能代表步态
    ac = np.correlate(k, k, "full")[len(k) - 1:]
    ac /= ac[0]
    lo, hi = 8, min(120, len(ac) - 1)
    return lo + int(np.argmax(ac[lo:hi]))


def quietest_start(j: np.ndarray, period: int, skip: int) -> int:
    """一个周期里"最安静"的那一帧当起点：混合进来的时候最不突兀。"""
    seg = j[skip:]
    vel = np.abs(np.diff(seg, axis=0)).sum(axis=1)
    candidates = range(0, min(period, len(vel) - 1))
    return skip + min(candidates, key=lambda i: vel[i])


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv", type=Path, nargs="+", help="infer_policy.py --save-csv 录的文件")
    ap.add_argument("--out", type=Path, default=Path("app/src/main/assets/duck/gaits.json"))
    ap.add_argument("--name", default="walk", help="片段名（walk / sit / stand…）")
    ap.add_argument("--skip", type=float, default=3.0, help="丢掉开头几秒（起步不稳）")
    ap.add_argument("--period", type=int, default=0, help="周期帧数，0 = 自动测")
    ap.add_argument("--vx", type=float, default=0.4, help="这段对应的前进指令（米/秒）")
    ap.add_argument("--vy", type=float, default=0.0)
    ap.add_argument("--wz", type=float, default=0.0, help="转身指令（弧度/秒）")
    ap.add_argument("--hz", type=float, default=50.0)
    ap.add_argument("--merge", action="store_true", help="并进已有的 gaits.json，而不是覆盖")
    args = ap.parse_args()

    clips = []
    if args.merge and args.out.is_file():
        clips = json.load(args.out.open()).get("clips", [])
        clips = [c for c in clips if c["name"] != args.name]

    for path in args.csv:
        t, j = load_offsets(path)
        skip = int(args.skip * args.hz)
        if len(j) - skip < 100:
            sys.exit(f"{path}: 只有 {len(j)} 帧，跳过 {skip} 帧之后不够分析")
        period = args.period or cycle_length(j)
        start = quietest_start(j, period, skip)
        frames = j[start:start + period]
        print(f"{path.name}: 周期 {period} 帧（{period / args.hz * 1000:.0f} ms，"
              f"{args.hz / period:.2f} Hz），起点第 {start} 帧")
        print(f"  各关节峰峰（度）: " + " ".join(
            f"{np.degrees(frames[:, k].max() - frames[:, k].min()):.0f}" for k in range(N_JOINTS)))
        clips.append({
            "name": args.name,
            "vx": args.vx, "vy": args.vy, "wz": args.wz,
            "frames": int(period),
            "offsets": [[round(float(v), 5) for v in frame] for frame in frames],
        })

    pack = {
        "format": 1,
        "source": "pollen-robotics/microduck-policies v5 · velstand.onnx（MuJoCo + BAM 实录）",
        "hz": args.hz,
        "joints": "每个片段 14 个数一帧，是**关节角相对 STAND 的偏移**（弧度），不含嘴（下标 9）",
        "clips": clips,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(pack, ensure_ascii=False, indent=1) + "\n")
    print(f"\n写出 {args.out}（{args.out.stat().st_size} 字节，{len(clips)} 个片段）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
