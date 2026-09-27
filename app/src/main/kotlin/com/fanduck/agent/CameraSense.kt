package com.fanduck.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 规格 §4.1 / §5：相机用 Camera2，只在 `SenseLoop` 和 `look_now` 里取帧。
 *
 * **看得见**：帧交给 ML Kit 的 image labeling（`Detector.kt` 里那些阈值、取几个、怎么说
 * 是纯逻辑，这里只负责把 JPEG 喂给模型）。bundled 版模型，全程在本机、不联网；
 * 认不出东西就是 `NO_DETECTOR_CAPTION`（§4.1 第 1 条）。
 *
 * 像素不出这台手机（§8）：帧只留在内存里，落盘的只有 `seen/latest.jpg`（§4.1 第 4 条，可选），
 * 而 `media` 字段不进云端请求——`IntentText.lineOf` 只取 id/at/text。发给云端的是**标签**
 * （"看到 人 87%"），不是图。
 *
 * 距离用手机上的接近传感器（听筒旁边那个：近=0，远=量程，通常 5 cm），除以 100 换成米。
 * 所以**盖住屏幕上半边**时「最近距离 0 米」会真的触发 §3.5 的近距离拒绝。没有就不拼。
 *
 * 相机回调在 `HandlerThread` 上，只往 volatile 里写值；**认图在 agent 线程上**
 * （`captionAndDistance` 由它调用，§4：摄像头回调把结果丢进 agent 线程）。模型跑一次几十毫秒，
 * 每 2 秒一拍，所以不另开线程；同一秒内重复问就复用上一次的结果（`look_now` 可能紧跟一拍）。
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
        // 建客户端会顺手开始把模型下下来（模型走 Play 服务），所以开机就建：不然第一次认图
        // 卡在下载上，只认出一句"未识别物体"。
        runCatching { labelerHolder.value }
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

    /** ML Kit 的识别器。第一次要认图时才建（懒），`shutdown()` 里关。 */
    private val labelerHolder = lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(LABEL_MIN_CONFIDENCE)
                .build(),
        )
    }
    private val labeler: ImageLabeler by labelerHolder

    /** 上一次认图的结果：同一秒里被问第二次就复用，别把模型跑两遍。 */
    private var cachedCaption: String? = null
    private var cachedAt = 0L

    override fun captionAndDistance(): Pair<String, Float?> =
        (if (running) caption() else NO_CAMERA_CAPTION) to nearestMeters

    private fun caption(): String {
        val jpeg = frame ?: return NO_DETECTOR_CAPTION   // 相机开着但还没取到帧
        val now = SystemClock.uptimeMillis()
        val cached = cachedCaption
        if (cached != null && now - cachedAt < LABEL_CACHE_MS) return cached
        val caption = captionOf(jpeg)
        cachedCaption = caption
        cachedAt = now
        return caption
    }

    /** 认一帧。任何失败（超时、模型没起来、图坏了）都当"没认出东西"，不让一拍打断 agent 线程。 */
    private fun captionOf(jpeg: ByteArray): String {
        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (e: Exception) {
            null
        } ?: return NO_DETECTOR_CAPTION
        return try {
            val found = Tasks.await(
                labeler.process(InputImage.fromBitmap(bitmap, 0)),
                LABEL_TIMEOUT_S,
                TimeUnit.SECONDS,
            )
            captionFromLabels(found.map { Label(labelName(it.text), it.confidence) })
        } catch (e: Exception) {
            Log.i(TAG, "认图失败：${e.message}")
            NO_DETECTOR_CAPTION
        } finally {
            bitmap.recycle()
        }
    }

    /** 退出时调用：把识别器关掉（没建过就不建）。 */
    fun shutdown() {
        if (labelerHolder.isInitialized()) runCatching { labeler.close() }
    }

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

        /** 认图结果的复用窗口：一拍 2 秒，`look_now` 可能紧跟其后。 */
        const val LABEL_CACHE_MS = 1_000L

        /** 认图超时。超过就当没认出来 —— agent 线程上还有一拍在等着。 */
        const val LABEL_TIMEOUT_S = 3L

        /** 相机没开时不要撒谎说"摄像头正常"（§4.1 那句话是给"没有检测器"写的）。 */
        const val NO_CAMERA_CAPTION = "摄像头没开"
    }
}
