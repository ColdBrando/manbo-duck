package com.fanduck.agent

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * 放《哈基米》并驱动舞步。曲子放在 `assets/dance/hakimi.mp3`（24.3 s，实测 122 BPM）。
 *
 * 界面时钟每帧问它一次 [frameOrNull]：在跳就给一帧（按拍子从 `Dance.kt` 那张八拍表里插值），
 * 不在跳就返回 null，交回给 `DuckMotion`。曲子放完自动收舞，收舞时用 250 ms 混回站立 ——
 * 不要从半空的一个姿势"啪"地跳回去。
 *
 * 时钟用 `System.nanoTime()`：界面时钟给过来的 `frameTimeNanos` 就是这个时基，混用
 * `uptimeMillis()` 会差一个常量偏移（两个时钟的起点不一样）。
 */
class DancePlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var startMs = 0L
    private var keys: List<DanceKey> = emptyList()
    private var loopMs = 0L

    /** 收舞：停下那一刻的姿势 → STAND。 */
    private var outroFrom: DuckFrame? = null
    private var outroStartMs = 0L

    val running: Boolean get() = player != null

    fun toggle() {
        if (running) stop() else start()
    }

    fun start() {
        stop()
        keys = hakimiDance()
        loopMs = danceLoopMs(keys)
        if (loopMs <= 0L) return
        try {
            val afd = context.assets.openFd(MUSIC)
            val media = MediaPlayer()
            media.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            // 注意写 this@DancePlayer.stop()：MediaPlayer 自己也有 stop()，
            // 在 apply/with 里裸写 stop() 会解析到它（只停播放、不释放），
            // 结果就是"音乐停了、鸭子一直跳"。
            media.setOnCompletionListener {
                Log.i(TAG, "曲子放完，收舞")
                this@DancePlayer.stop()
            }
            media.prepare()
            media.start()
            player = media
            startMs = nanoMs()
            Log.i(TAG, "开始跳：${loopMs} ms 一循环，配 $MUSIC")
        } catch (e: Exception) {
            Log.i(TAG, "放不出来：${e.message}")
            stop()
        }
    }

    fun stop() {
        if (player == null) return
        runCatching { player?.release() }
        player = null
        // 从"停下那一刻的姿势"混回站立（key 表还在，用它算出当前帧）
        outroFrom = danceFrame(keys, (nanoMs() - startMs) % loopMs)
        outroStartMs = nanoMs()
    }

    /** 界面时钟每帧问一次；不在跳舞、也没在收舞就返回 null。 */
    fun frameOrNull(nowMs: Long): DuckFrame? {
        if (player != null) return danceFrame(keys, (nowMs - startMs) % loopMs)
        val from = outroFrom ?: return null
        val t = (nowMs - outroStartMs).toFloat() / OUTRO_MS
        if (t >= 1f) {
            outroFrom = null
            return null
        }
        val s = smoothstep(t)
        return DuckFrame(
            joints = FloatArray(15) { i -> from.joints[i] + (STAND[i] - from.joints[i]) * s },
            x = from.x + (0f - from.x) * s,
            z = 0f,
            yaw = from.yaw + (0f - from.yaw) * s,
        )
    }

    private fun nanoMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val TAG = "duck-dance"
        const val MUSIC = "dance/hakimi.mp3"
        const val OUTRO_MS = 250f
    }
}
