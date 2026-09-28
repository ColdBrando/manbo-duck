// 桌面侧的步态播放器。把 `assets/duck/gaits.json`（真机策略烘出来的步态）放到屏幕上这只鸭子身上。
//
// 这段脚本是**编译进二进制**的（lib.rs 里 include_str!），不在 assets 里 —— 因为
// `app/src/main/assets/duck/` 是和 Android 共用的目录，桌面专属的东西不该往里塞。
//
// 它干的是 Android 那边 `DuckMotion.kt` + `Gait.kt` 的同一件事：按指令积分出地面位置，
// 取步态片段的一帧摊到 15 个关节角上，30 fps 推给 duck.js。两边算的是同一套数，
// 所以手机和桌面上鸭子走的步态是一样的。
//
// 有意**没有**实现 Android 那套手写步态（膝盖摆动 / 踏步 / 前倾）：那部分是给"没有素材"
// 兜底的，而真机的 velstand 策略在低于 WALK_CLIP_MIN 时本来就选择站着 —— 所以这里
// 低于门槛就站着呼吸，反而和真机行为一致。也是避免把另一个会话正在改的代码抄第二遍。
(function () {
  'use strict';

  // ---------------------------------------------------------------------------
  // 常量。凡是和 Kotlin 那边重名的，值都对着 DuckMotion.kt / Gait.kt 抄，别各写各的。
  // ---------------------------------------------------------------------------

  /** 基准站姿，15 个关节角（弧度）。= DuckMotion.STAND */
  var STAND = [
    0, -0.0873, -0.4579, -0.0049, 0.4530,
    0.3491, 0.3491, 0, 0, 0,
    0, 0.0873, 0.4579, 0.0049, -0.4530,
  ];

  /** 速度的一阶滞后时间常数（秒）。起停的"重量感"全在这个数上。= ACCEL_TAU */
  var ACCEL_TAU = 0.12;

  /** 指令低于这个速度就不播步态。velstand 策略实测 0.25 站着、0.30 走。= WALK_CLIP_MIN */
  var WALK_CLIP_MIN = 0.3;

  /** 手写姿态和素材之间切换用多久糊过去（秒）。= CLIP_FADE_S */
  var CLIP_FADE_S = 0.2;

  /** 待机呼吸：约 3.3 秒一次，加在脖子上。= BREATH_HZ / BREATH_NECK */
  var BREATH_HZ = 0.30;
  var BREATH_NECK = 0.030;

  /**
   * 命令速度（米/秒）。定死在 0.4 是有意的：烘出来的那段 clip 就是 0.4 m/s 录的，
   * 步频是那段录制的固有属性。命令成别的速度、腿还是按 0.4 的频率倒，就会"滑行"——
   * 这正是项目里反复修掉的那个观感（README：走路不再像滑行）。
   *
   * 想要更快（"跑"），得去 microduck_rl 按更高指令再录一段烘进来，不是在这里加速度。
   */
  var WALK_SPEED = 0.4;

  /** 原地转身的角速度（弧度/秒）。yaw 增大 = 鸭子朝自己左侧转（+X 是鸭子的左侧）。 */
  var TURN_RATE = 1.0;

  /**
   * 步进周期。Android 那边是 Choreographer 约 30 fps，这里对齐 —— 不只是省 CPU：
   * duck.js 的相机软跟随是**按帧**追 6%（CAM_LAG），60 fps 下时间常数会短一半，
   * 观感就和手机上不是同一只鸭子了。
   */
  var STEP_MS = 1000 / 30;

  // ---------------------------------------------------------------------------
  // 素材
  // ---------------------------------------------------------------------------

  /** hz 全包共用一个（gaits.json 顶层字段），和 GaitClip 一样。 */
  var packHz = 50;

  /** 一段周期动作。对应 Gait.kt 的 GaitClip。 */
  function makeClip(c) {
    return {
      name: c.name,
      vx: c.vx || 0,
      vy: c.vy || 0,
      wz: c.wz || 0,
      frames: c.offsets.length,
      offsets: c.offsets,
    };
  }

  /**
   * 按相位取一帧，摊进 15 个关节角。对应 GaitClip.frameAt。
   *
   * 偏移只加在 14 个下标上，**跳过下标 9（嘴）** —— 嘴是我们自己的（说话时张开），
   * 真机策略的观测里也没有它。映射是 k<9 ? k : k+1。
   *
   * 帧间线性插值、首尾环绕，所以一个周期能无缝循环。
   */
  function frameAt(clip, phase, base) {
    var out = base.slice();
    if (!clip.frames) return out;
    var x = ((phase % 1) + 1) % 1 * clip.frames;
    var i = Math.floor(x) % clip.frames;
    var j = (i + 1) % clip.frames;
    var t = x - Math.floor(x);
    for (var k = 0; k < 14; k++) {
      var idx = k < 9 ? k : k + 1;
      var a = clip.offsets[i][k] || 0;
      var b = clip.offsets[j][k] || 0;
      out[idx] = base[idx] + a + (b - a) * t;
    }
    return out;
  }

  /** 按指令挑一段。对应 GaitPack.forCommand —— 只有一段素材时就是"方向对得上就用"。 */
  function pickClip(vx, vy, wz) {
    if (!clips) return null;
    for (var i = 0; i < clips.length; i++) {
      var c = clips[i];
      if (c.vx !== 0 && vx * c.vx <= 0) continue;
      if (c.vy !== 0 && vy * c.vy <= 0) continue;
      if (c.wz !== 0 && wz * c.wz <= 0) continue;
      return c;
    }
    return null;
  }

  var clips = null;

  function loadGaits() {
    // 相对路径就够：Tauri 用自定义协议把 frontendDist 当站点伺服，fetch 是同源的
    // （duck.js 顶部那条"file:// 的 fetch 会被 CORS 拦掉"是 Android WebView 的限制，这里不适用）
    fetch(new URL('gaits.json', document.baseURI).href)
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (p) {
        if (!p || !p.clips || !p.clips.length) {
          console.log('gait: gaits.json 里没有片段，鸭子只站着');
          return;
        }
        packHz = p.hz || 50;
        clips = p.clips.map(makeClip).filter(function (c) { return c.frames > 0; });
        console.log('gait: 载入 ' + clips.length + ' 段素材 @' + packHz + 'Hz — ' +
          clips.map(function (c) { return c.name + '(' + c.frames + '帧)'; }).join(', '));
      })
      .catch(function (e) {
        // 素材坏了不能让鸭子跟着崩 —— 退回"站着"，和没有素材时一样
        console.log('gait: gaits.json 读不了，鸭子只站着 — ' + e);
      });
  }

  // ---------------------------------------------------------------------------
  // 状态
  // ---------------------------------------------------------------------------

  /** 命令速度（按键写进来）和真正在用的速度（一阶滞后追它）。 */
  var cmd = { vx: 0, vy: 0, wz: 0 };
  var smooth = { vx: 0, vy: 0, wz: 0 };

  var x = 0, z = 0, yaw = 0;
  var clipPhase = 0, clipWeight = 0, breathPhase = 0;

  function step(dt) {
    var k = Math.min(1, dt / ACCEL_TAU);
    smooth.vx += (cmd.vx - smooth.vx) * k;
    smooth.vy += (cmd.vy - smooth.vy) * k;
    smooth.wz += (cmd.wz - smooth.wz) * k;

    // 位置用**平滑之后**的速度积分，所以起停有加速/减速（对 DuckMotion.sample 那三行）
    x += (smooth.vx * Math.sin(yaw) + smooth.vy * Math.cos(yaw)) * dt;
    z += (smooth.vx * Math.cos(yaw) - smooth.vy * Math.sin(yaw)) * dt;
    yaw += smooth.wz * dt;

    breathPhase += 2 * Math.PI * BREATH_HZ * dt;

    var speed = Math.sqrt(smooth.vx * smooth.vx + smooth.vy * smooth.vy);
    var walking = speed >= WALK_CLIP_MIN;

    // base 不带呼吸：素材的偏移要摊在这个上面（对 frameAt 拿到的 base）
    var base = STAND.slice();

    // 呼吸挂在脖子上（下标 5），只在没在走的时候看得见。
    // 为什么都加在脖子：这个骨架里躯干是根节点、整机只有 x/z/yaw 三个自由度，
    // 转髋/屈膝都推不动身体，能看出"它在使劲"的只有脖子和头。见 DuckMotion.kt 的注释。
    var joints = base.slice();
    if (!walking) joints[5] += Math.sin(breathPhase) * BREATH_NECK;

    var clip = walking ? pickClip(smooth.vx, smooth.vy, smooth.wz) : null;
    var fade = Math.min(1, dt / CLIP_FADE_S);
    if (clip) {
      clipPhase += (packHz / clip.frames) * dt;
      clipWeight += (1 - clipWeight) * fade;
      var cj = frameAt(clip, clipPhase, base);
      for (var i = 0; i < 15; i++) joints[i] += (cj[i] - joints[i]) * clipWeight;
    } else {
      clipWeight += (0 - clipWeight) * fade;
    }

    push(joints);
  }

  function push(joints) {
    // 必须 window.duck：duck.js 顶层有 `const duck = new THREE.Group()`，
    // 它在全局词法环境里遮蔽了 window.duck（doc/gotchas.md 第 1 条）
    if (window.duck && typeof window.duck.setFrame === 'function') {
      window.duck.setFrame(joints, x, z, yaw);
    }
  }

  // ---------------------------------------------------------------------------
  // 键盘
  // ---------------------------------------------------------------------------
  //
  // 桌面没有触摸屏，手机上"按住屏幕说一句"那套在这儿没有对应物。这一版先只做运动：
  // 方向键 = 走 / 退 / 左转 / 右转，空格 = 停。以后接上 agent 时，这里换成
  // 云端下发的 velocity 指令即可（DuckMotion 那一层的接口是一样的）。

  var down = Object.create(null);
  var HANDLED = {
    ArrowUp: 1, ArrowDown: 1, ArrowLeft: 1, ArrowRight: 1,
    w: 1, a: 1, s: 1, d: 1, W: 1, A: 1, S: 1, D: 1, ' ': 1,
  };

  /** 用 hasOwnProperty 查，别写 HANDLED[e.key] —— 那是普通对象，会顺着原型链
   *  摸到 constructor / valueOf 这些名字上去，把不相干的键当成已处理。 */
  function handled(key) {
    return Object.prototype.hasOwnProperty.call(HANDLED, key);
  }

  function refreshCmd() {
    if (down[' ']) { for (var k in down) down[k] = false; }
    var fwd = down.ArrowUp || down.w || down.W;
    var back = down.ArrowDown || down.s || down.S;
    var left = down.ArrowLeft || down.a || down.A;
    var right = down.ArrowRight || down.d || down.D;
    cmd.vx = (fwd ? WALK_SPEED : 0) + (back ? -WALK_SPEED : 0);
    cmd.wz = (left ? TURN_RATE : 0) + (right ? -TURN_RATE : 0);
    cmd.vy = 0;
  }

  window.addEventListener('keydown', function (e) {
    if (!handled(e.key)) return;
    down[e.key] = true;
    refreshCmd();
    e.preventDefault();   // 别让方向键去滚页面
  });

  window.addEventListener('keyup', function (e) {
    if (!handled(e.key)) return;
    down[e.key] = false;
    refreshCmd();
    e.preventDefault();
  });

  // 窗口失焦时把按键全放掉，否则切走再回来会"卡着一直走"
  window.addEventListener('blur', function () {
    for (var k in down) down[k] = false;
    refreshCmd();
  });

  // ---------------------------------------------------------------------------
  // 主循环
  // ---------------------------------------------------------------------------

  var STEP_S = STEP_MS / 1000;
  var lastMs = 0;
  var acc = 0;

  function loop(nowMs) {
    requestAnimationFrame(loop);
    if (!lastMs) { lastMs = nowMs; return; }
    // 和 DuckMotion.sample 一样把单帧 dt 掐在 100 ms 内：窗口被挂起再回来，
    // 不掐的话鸭子会瞬移一大段
    acc += Math.min(100, nowMs - lastMs) / 1000;
    lastMs = nowMs;

    var n = 0;
    while (acc >= STEP_S && n < 8) {
      step(STEP_S);
      acc -= STEP_S;
      n++;
    }
    if (acc > STEP_S * 8) acc = 0;   // 落后太多就别追了，丢掉
  }

  window.addEventListener('load', loadGaits);
  requestAnimationFrame(loop);

  console.log('gait: 方向键走/转身，空格停');
})();
