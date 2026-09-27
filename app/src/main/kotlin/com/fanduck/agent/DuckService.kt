package com.fanduck.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 规格 §5：「前台服务持有麦克风，并保持屏幕上的鸭子在动。」通知栏不是可选项 ——
 * 前台服务必须带一条通知，否则系统会直接把它杀掉（Android 13 起还要用户给
 * `POST_NOTIFICATIONS`，没给也照样跑，只是通知看不见）。
 *
 * **什么时候起**：MainActivity 退到后台（`onPause`）时起，回到前台（`onResume`）或销毁时停。
 * 不能在更晚的地方起 —— Android 12 起不允许从后台启动前台服务，而在 `onPause` 那一刻
 * 这个应用还算在前台。见 `MainActivity.onPause`。
 *
 * **它干什么**：把进程钉住（agent 线程、TTS、排队里的那一句都还能走完），并让"这台应用
 * 正在用麦克风"在系统那里是合法的（`foregroundServiceType="microphone"`）。识别器本身不在
 * 这里 —— 退到后台之后由 MainActivity 把它切成连续听（`VoiceInput.setContinuous(true)`），
 * 服务只是让那件事合法。**那个 6.2 秒一轮的代价见 §5 和 `VoiceInput` 的注释。**
 *
 * 点通知回到应用 = 回到前台 = 停止连续听。这是唯一一个"让它闭嘴"的入口。
 */
class DuckService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "鸭子醒着（前台服务起来了）")
        // 被系统杀掉就杀掉，别自己起来：这个服务只有在用户主动把鸭子留在后台时才有意义。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "鸭子睡了（前台服务停了）")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "鸭子醒着", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "退到后台时让鸭子继续听" },
            )
        }
        // 点一下回到应用：回到前台就会停掉连续听（见类注释）。
        val back = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("鸭子醒着")
            .setContentText("退到后台也在听，点一下回来")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(back)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "duck-service"
        private const val CHANNEL_ID = "duck-alive"
        private const val NOTIFICATION_ID = 1

        /** 退到后台时起这个服务（`onPause` 里调，那时还算前台）。 */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DuckService::class.java)) }
                .onFailure { Log.i(TAG, "前台服务没起来：${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DuckService::class.java)) }
        }
    }
}
