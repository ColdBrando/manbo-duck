# duckgait —— 把真机训出来的步态烘成屏幕鸭子能播的东西

屏幕鸭子走路的动作**不是手调的**：它是 `pollen-robotics/microduck-policies` v5 的
`velstand.onnx` 在 MuJoCo + 真机 BAM 电机模型里跑出来的轨迹，切一个周期存成
`app/src/main/assets/duck/gaits.json`，由 `core/Gait.kt` 播放。

```
velstand.onnx（793 KB，HuggingFace）
   │  microduck_rl/scripts/infer_policy.py --save-csv（50 Hz，61 维观测）
   ▼
w40.csv（14 秒，每帧 61 个观测数）
   │  bake_gait.py：找周期 → 挑最安静的起点 → 切一个循环
   ▼
gaits.json（20 帧 × 14 个关节，4.6 KB）
   │  core/Gait.kt 按相位插值播放 + 200 ms 交叉过渡
   ▼
屏幕上的鸭子走的就是真机训出来的那套
```

## 烘一个新的

```bash
python3 tools/duckgait/bake_gait.py w40.csv --name walk --vx 0.4
python3 tools/duckgait/bake_gait.py turn.csv --name turn --wz 1.0 --merge   # 并进去，不覆盖
```

`--merge` 之后 `GaitPack.forCommand` 会按方向挑片段（现在只有"前进"一段）。
换出来的效果在 logcat 里：`duck-motion: 步态素材：walk 20 帧 / 400 ms`。

## 怎么录（在 `microduck_rl` 里）

```bash
# 1) 环境。不需要 mjlab/torch/CUDA —— infer_policy.py 特意不依赖它们
uv venv /tmp/duckrl --python 3.12
uv pip install --python /tmp/duckrl/bin/python mujoco onnxruntime
# BAM 不在 PyPI（装到的是 1.0.2，接口对不上）：要 uv.lock 里钉的那个 commit
uv pip install --python /tmp/duckrl/bin/python \
    "git+https://github.com/Rhoban/bam.git@62bd8ce12154340be97e06f7f41a0ca8f116d967"

# 2) 策略
python3 -c "from huggingface_hub import hf_hub_download as d; \
  print(d('pollen-robotics/microduck-policies','velstand.onnx',revision='v5'))"

# 3) 录（--new-cmd-obs 是给 v5 的 61 维观测用的；不加会报 "Got: 51 Expected: 61"）
/tmp/duckrl/bin/python infer_headless.py --walking <velstand.onnx> --new-cmd-obs \
    --lin-vel-x 0.4 --save-csv w40.csv
```

### `infer_headless.py` 是什么

`infer_policy.py` 原样 + 一处替换：macOS 上 `mujoco.viewer.launch_passive` 要求用
`mjpython` 跑（要开窗口、要人按键），无人值守录不了。把它换成一个空壳 viewer，
`is_running()` 到 `DUCK_RUN_S` 秒就返回 False，主循环正常退出、脚本照样落盘 CSV。

补丁就是把 `mujoco.viewer.launch_passive(...) as viewer:` 换成 `_NullViewer() as viewer:`，
再插一个类进去 —— 见 `bake_gait.py` 顶部的注释。

## 踩过的（改之前先看）

1. **`--no-bam` 录出来的是静止**。策略是对着 BAM 电机模型（电压/摩擦/电流）训的，
   换成 XML 里的普通位置执行器它就不走了：鸭子直立、指令也收到了，但动作恒定不变。
2. **`velstand` 有死区**：实测 **0.25 m/s 站着、0.30 走**。要让它走，指令得 ≥0.3。
   （屏幕鸭子没照抄这个死区，理由见 `core/Gait.kt`。）
3. **周期正好 20 帧 = 400 ms = 2.5 Hz**（自相关测出来的），而且 0.30/0.35/0.40 三档的
   步态几乎一样 —— 它不是"越快步子越大"，而是"要么站、要么用这一个步态走"。
4. **头是跟着摆的**（head_yaw 峰峰 15°）—— 策略拿头配平。手调的时候想不到这个，
   这也是它看起来比正弦步态生动的最大来源。
