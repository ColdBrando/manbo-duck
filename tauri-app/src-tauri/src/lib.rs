// 桌面壳。这一版只做一件事：开一个窗口，把 `app/src/main/assets/duck/` 原样装进来。
//
// 前端一个字节都不复制 —— tauri.conf.json 里的 frontendDist 直接指到 Android 那份 assets
// （`../../app/src/main/assets/duck`，相对于 src-tauri）。所以 duck.js / duck-meshes.js
// 全项目只有一份，改完两边同时生效，不会出现"手机上是新的、桌面还是旧的"。
//
// Tauri 在这一点上就是个静态 Web 宿主：给它一个目录，它用系统 WebView 渲染里面的
// index.html，和 Android 那边 WebView 干的是同一件事。所以 duck.js 里那些为 WebView 写的
// 判断（`<script>` 标签而不是 fetch 取 base64，见 duck.js 顶部注释）在这里照样成立，不用改。

/// 窗口是唯一一处"不是原样"的地方。
///
/// duck.js 在**解析时**调了一次 `renderer.setSize(window.innerWidth, window.innerHeight)`，
/// 之后再没人管尺寸了 —— 而 WebGL canvas 的 CSS 尺寸是 setSize 写死的，窗口被拖动时
/// 不会自己跟着变，表现为画面停在旧尺寸、周围留一圈黑边。
///
/// Android 那边是靠 DuckView 在页面加载完成后调一次 `window.duck.resize()` 解决的
/// （duck.js 里"规格里没有这个函数"那条注释）。桌面窗口能随时拖，所以这里得挂到
/// resize 事件上，而不是只调一次。
///
/// 写成初始化脚本注入，是为了不动 assets 里任何一个文件 —— 这才是"原样加载"。
///
/// 注意是 `window.duck` 不是 `duck`：duck.js 顶层有 `const duck = new THREE.Group()`，
/// 它在全局词法环境里遮蔽了 `window.duck`（doc/gotchas.md 第 1 条，踩过）。
/// 另外脚本在页面脚本之前执行，那时 window.duck 还不存在，所以必须判空、且只在事件里调用。
const RESIZE_SCRIPT: &str = r#"
(function () {
  function resize() {
    if (window.duck && typeof window.duck.resize === 'function') window.duck.resize();
  }
  // 'load' 兜一次：万一这里也遇上 Android 那边"解析时还没布局完"的时序问题
  window.addEventListener('load', resize);
  window.addEventListener('resize', resize);
})();
"#;

/// 步态播放器。编译期读进来，和上面的 resize 脚本拼成一段初始化脚本。
///
/// 放在 `src/` 而不是 assets 里，是因为 `app/src/main/assets/duck/` 是和 Android 共用的目录，
/// 桌面专属的东西不该往里塞。改这个 .js 会让 cargo 重编（include_str! 是被跟踪的依赖）。
const GAIT_PLAYER: &str = include_str!("gait-player.js");

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // 窗口在这里建、而不是写在 tauri.conf.json 的 app.windows 里，就为了能挂
        // initialization_script —— 那个字段配置里没有。
        .setup(|app| {
            // 两段都是"不改 assets 一个字节"的注入：resize 管窗口尺寸，gait-play 管让鸭子走起来
            let init_script = format!("{RESIZE_SCRIPT}\n{GAIT_PLAYER}");
            tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::default())
                .title("duck")
                // 竖屏比例。手机上这块屏就是鸭子的舞台，桌面上让它保持差不多的取景
                // （duck.js 的相机按窗口宽高比算 FOV，横过来鸭子会显得很小）
                .inner_size(520.0, 880.0)
                .min_inner_size(320.0, 480.0)
                .initialization_script(&init_script)
                .build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("duck 桌面壳启动失败");
}
