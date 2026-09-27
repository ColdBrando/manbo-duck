package com.fanduck.agent

import android.util.Log
import android.view.Choreographer
import android.webkit.WebView

/**
 * 规格 §6.4 / §6.5：界面时钟。两次采样间隔小于 32 ms 就跳过，大约 30 帧。
 * 这不是电机循环，agent 线程也不要碰 WebView——evaluateJavascript 只能在主线程。
 */
class DuckView(
    private val web: WebView,
    private val motion: DuckMotion,
    /** 跳舞时由它出帧（返回 null 就交回 DuckMotion）。规格里没有跳舞，见 Dance.kt。 */
    private val dance: ((Long) -> DuckFrame?)? = null,
) {
    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastFrameMs = 0L
    private var dancing = false
    private val callback = Choreographer.FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }

    fun start() {
        if (running) return
        running = true
        lastFrameMs = 0L
        choreographer.postFrameCallback(callback)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(callback)
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (!running) return
        val nowMs = frameTimeNanos / 1_000_000L
        if (nowMs - lastFrameMs >= FRAME_SKIP_MS) {
            lastFrameMs = nowMs
            val danced = dance?.invoke(nowMs)
            if ((danced != null) != dancing) {
                dancing = danced != null
                Log.i("duck-dance", if (dancing) "这一帧起由舞步出帧" else "收舞完成，交回 DuckMotion")
            }
            pushFrame(web, danced ?: motion.sample(nowMs))
        }
        choreographer.postFrameCallback(callback)
    }

    private companion object {
        const val FRAME_SKIP_MS = 32L
    }
}

/**
 * 规格 §6.5：15 个数连成数组字面量，不拼文件路径、不拼用户说的话。
 * Float.toString() 的小数点是 .，不随系统语言变。
 *
 * 注意这里必须写 `window.duck` 而不是 `duck`：duck.js 里有顶层 `const duck = new THREE.Group()`，
 * 它在全局词法环境里遮蔽了 `window.duck`，直接写 `duck.setFrame(...)` 会报
 * "duck.setFrame is not a function"，屏幕上什么都没有。规格 §6.4 和 §6.5 拼在一起才会暴露。
 */
fun pushFrame(web: WebView, frame: DuckFrame) {
    val joints = frame.joints.joinToString(",")
    web.evaluateJavascript(
        "window.duck.setFrame([$joints],${frame.x},${frame.z},${frame.yaw})",
        null,
    )
}

/**
 * 页面加载完成后调一次，把 canvas 尺寸对齐到真正布局好的视口，并打一条诊断日志（logcat 里
 * 用 `adb logcat | grep CONSOLE` 看）。第二次加载后如果屏幕还是黑的，这条日志就是第一现场。
 */
/**
 * 换机位：8 个视角，绕鸭子 45° 一格，0 = 正前方。
 * 规格里没有（§6.5 只写了一个机位），是调试面板用来换角度看模型的入口。
 */
fun setView(web: WebView, index: Int) {
    web.evaluateJavascript("window.duck.setView($index)", null)
}

fun resizeAndDiagnose(web: WebView) {
    web.evaluateJavascript(
        """
        (function () {
          window.duck.resize();
          var c = document.querySelector('canvas');
          var gl = renderer.getContext();
          console.log('duck diag viewport=' + window.innerWidth + 'x' + window.innerHeight +
                      ' canvas=' + c.width + 'x' + c.height +
                      ' calls=' + renderer.info.render.calls +
                      ' glError=' + gl.getError());
        })();
        """.trimIndent(),
        null,
    )
}
