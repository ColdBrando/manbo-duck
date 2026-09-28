#!/usr/bin/env python3
"""收浏览器里发来的诊断，落到文件。

为什么不用截图：全屏 screencapture 会抓进用户正在做的别的事（付钱、聊天……），
既冒犯又不准 —— 窗口被遮住时截到的是遮挡它的东西。诊断走这条 HTTP 通道，
只看我要的那几个数。

WebView 里 POST 用的是 mode:'no-cors'，所以不校验 Origin、也不回 CORS 头。
"""
import http.server
import socketserver
import sys
from datetime import datetime
from pathlib import Path

# 关键：日志**不能**写在被 Tauri dev 监视的目录里（就是 frontendDist 那个目录）。
# 写在那里会让 watcher 以为前端变了 → 触发页面重载 → 页面又发一条 → 无限重载。
# 踩过：表现为页面每 1.5 秒重启一次，屏幕全黑，什么都跑不起来。
LOG = Path("/tmp/duck-telemetry.log")
PORT = 8791


class H(http.server.BaseHTTPRequestHandler):
    def _drain(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n).decode("utf-8", "replace") if n else ""

    def do_POST(self):
        body = self._drain()
        if self.path.startswith("/shot"):
            # 页面自己把 canvas 传过来。比 screencapture 干净得多：
            # 不抓用户屏幕上别的东西，窗口被遮住也照样拿得到。
            import base64
            raw = body.split(",", 1)[-1]
            out = Path(f"/tmp/duck-canvas-{self.path.rsplit('/', 1)[-1]}.png")
            out.write_bytes(base64.b64decode(raw))
            with LOG.open("a") as f:
                f.write(f"--- {datetime.now():%H:%M:%S} --- 截图 {out} "
                        f"({out.stat().st_size} 字节)\n")
        else:
            with LOG.open("a") as f:
                f.write(f"--- {datetime.now():%H:%M:%S} ---\n{body}\n")
        self.send_response(204)
        self.end_headers()

    def do_OPTIONS(self):          # 万一将来要带 CORS 头
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def log_message(self, *a):
        pass                        # 别刷屏


if __name__ == "__main__":
    LOG.write_text("")
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("127.0.0.1", PORT), H) as srv:
        print(f"telemetry 监听 127.0.0.1:{PORT} → {LOG}", flush=True)
        srv.serve_forever()
