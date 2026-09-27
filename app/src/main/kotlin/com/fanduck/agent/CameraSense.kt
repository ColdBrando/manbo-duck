package com.fanduck.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File

/**
 * 规格 §4.1 / §5：相机用 Camera2，只在 `SenseLoop` 和 `look_now` 里取帧。
 *
 * 没有检测器，所以那句话就是 `NO_DETECTOR_CAPTION`（§4.1 第 1 条）—— 相机现在是"取到了帧"
 * 的证明，以及以后接检测器的位置。像素不出这台手机（§8）：帧只留在内存里，
 * 落盘的只有 `seen/latest.jpg`（§4.1 第 4 条，可选），而 `media` 字段不进云端请求
 * ——`IntentText.lineOf` 只取 id/at/text。
 *
 * 距离用手机上的接近传感器（听筒旁边那个：近=0，远=量程，通常 5 cm），除以 100 换成米。
 * 所以**盖住屏幕上半边**时「最近距离 0 米」会真的触发 §3.5 的近距离拒绝。没有就不拼。
 *
 * 相机回调在 `HandlerThread` 上，只往这两个 volatile 里写值；读它们的是 agent 线程
 * （§4：摄像头回调把结果丢进 agent 线程，不要在回调里访问云端）。
 */
class CameraSense(private val context: Context) : SensePort {

    private val thread = HandlerThread("duck-camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @Volatile
    private var frame: ByteArray? = null

    @Volatile
    private var running = false

    @Volatile
    private var nearestMeters: Float? = null

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // 接近传感器是"挡住 / 没挡住"，不是测距仪：量程之外什么都看不见，远端的读数就等于
            // 量程（通常 5 cm）。所以只有**读数小于量程**才算"前方有东西"，报成米；否则当作没有
            // 读数，不拼那一句（§4.1 第 2 条）。按原样报会把真机变成永远"最近距离 0.05 米"，
            // §3.5 的近距离拒绝会把一切前进动作都挡掉。
            val value = event.values.firstOrNull()
            nearestMeters = if (value != null && value < event.sensor.maximumRange) value / 100f else null
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "没有相机权限，取帧没开")
            return
        }
        running = true
        proximity?.let { sensors.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        handler.post { open() }
    }

    fun stop() {
        running = false
        sensors.unregisterListener(proximityListener)
        handler.post {
            closeQuietly()
            frame = null
        }
    }

    override fun captionAndDistance(): Pair<String, Float?> =
        (if (running) NO_DETECTOR_CAPTION else NO_CAMERA_CAPTION) to nearestMeters

    /**
     * 调试和手测用：把内存里最新那一帧覆盖写到 `seen/latest.jpg`（§4.1 第 4 条）。
     * 返回写进去的字节数，还没有帧就返回 -1。
     */
    fun saveLatestFrame(file: File): Int {
        val bytes = frame ?: return -1
        return try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            bytes.size
        } catch (e: Exception) {
            Log.i(TAG, "写 latest.jpg 失败：${e.message}")
            -1
        }
    }

    private fun open() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull { candidate ->
            manager.getCameraCharacteristics(candidate)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
        if (id == null) {
            Log.i(TAG, "这台设备没有相机")
            running = false
            return
        }
        reader = ImageReader.newInstance(FRAME_WIDTH, FRAME_HEIGHT, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ source -> takeFrame(source) }, handler)
        }
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                startFrames()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                device = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.i(TAG, "相机出错 $error")
                camera.close()
                device = null
            }
        }
        try {
            manager.openCamera(id, callback, handler)
        } catch (e: Exception) {
            Log.i(TAG, "打不开相机：${e.message}")
            running = false
        }
    }

    private fun startFrames() {
        val camera = device ?: return
        val surface = reader?.surface ?: return
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
        }
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    runCatching { configured.setRepeatingRequest(request.build(), null, handler) }
                        .onFailure { Log.i(TAG, "取帧没起来：${it.message}") }
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    Log.i(TAG, "相机会话没配起来")
                }
            },
            handler,
        )
    }

    private fun takeFrame(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            frame = bytes
        } finally {
            image.close()
        }
    }

    private fun closeQuietly() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
    }

    private companion object {
        const val TAG = "duck-sense"
        const val FRAME_WIDTH = 640
        const val FRAME_HEIGHT = 480

        /** 相机没开时不要撒谎说"摄像头正常"（§4.1 那句话是给"没有检测器"写的）。 */
        const val NO_CAMERA_CAPTION = "摄像头没开"
    }
}
