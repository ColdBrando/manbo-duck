// Windows 下 release 构建不要额外弹一个控制台窗口
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    duck_app_lib::run()
}
