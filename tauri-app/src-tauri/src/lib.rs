// 桌面壳的 Rust 侧。这里**几乎没有逻辑** —— 开一个窗口，剩下的全在前端。
//
// 窗口是给 `web/` 里那个页面的：它自己加载 MuJoCo(WASM) 算物理、onnxruntime-web 跑策略、
// three.js 画。Rust 这侧不参与仿真，也不碰前端的资源。
//
// 早先有一版是"把 Android 的 assets 原样加载进来 + 注入两段脚本"（resize 和步态播放器），
// 走的是"离线烘好关节角、前端放动画"那条路。现在换成前端实时跑策略了，那两段注入
// 都不需要：resize 由 app.js 自己监听，动作由 app.js 自己的控制循环驱动。
// 旧的 gait-player.js 已删。

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // 窗口建在这里而不是 tauri.conf.json 的 app.windows，是为了能按窗口设
        // inner_size / min_inner_size —— 竖屏比例，和手机上的取景一致。
        .setup(|app| {
            tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::default())
                .title("duck")
                .inner_size(520.0, 880.0)
                .min_inner_size(320.0, 480.0)
                .build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("duck 桌面壳启动失败");
}
