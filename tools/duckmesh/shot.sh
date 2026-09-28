#!/bin/bash
# 用法: shot.sh <输出png> <15个关节角,逗号分隔> [宽x高] [机位0-7]
# 用 headless Chrome 渲一张姿态图（和 app 里同一份 duck.js / pose-probe.html）。
# 机位默认 0 = 正前方；核"往前倾 / 往后坐"这类要看侧面的符号时传 6（鸭子右侧）。
set -e
OUT="$1"; POSE="$2"; SIZE="${3:-700x760}"; VIEW="${4:-0}"
DIR="$(cd "$(dirname "$0")/../../app/src/main/assets/duck" && pwd)"
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars \
  --window-size="${SIZE/x/,}" --virtual-time-budget=8000 --screenshot="$OUT" \
  "file://$DIR/pose-probe.html?pose=$POSE&view=$VIEW" >/dev/null 2>&1
echo "$OUT $(stat -f%z "$OUT") bytes"
