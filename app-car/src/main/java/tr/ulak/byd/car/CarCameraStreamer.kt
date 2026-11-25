package tr.ulak.byd.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class CarCameraStreamer(
    private val context: Context,
    private val config: UlakCarConfig,
    private val isSentryEnabled: () -> Boolean,
    private val onMotionDetected: (Long) -> Unit,
    private val onCloudFrameReady: (cameraIndex: Int, base64Jpeg: String) -> Unit,
) {

    companion object {
        private const val TAG = "UlakCameraStreamer"
        const val HTTP_PORT = 45456
        private const val STREAM_WIDTH = 640
        private const val STREAM_HEIGHT = 360
        private const val MOTION_THRESHOLD = 18.0
        private const val MOTION_COOLDOWN_MS = 10_000L
        private const val CLOUD_STREAM_AUTO_TIMEOUT_MS = 5 * 60 * 1000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var activeCameraIndex: Int = 0
    @Volatile private var availableCameraIds: List<String> = emptyList()
    @Volatile private var cameraHardwareActive: Boolean = false
    @Volatile private var lastMotionAlertMs: Long = 0L
    @Volatile private var cloudStreamingActive: Boolean = false
    @Volatile private var cloudStreamExpiresAtMs: Long = 0L

    private var prevLumaSignature: ByteArray? = null
    private val activeClients = AtomicInteger(0)

    fun getActiveClientCount(): Int = activeClients.get()
    fun getLastMotionTimestamp(): Long = lastMotionAlertMs
    fun isCameraHardwareActive(): Boolean = cameraHardwareActive
    fun isCloudStreamingActive(): Boolean = cloudStreamingActive

    fun start() {
        startCameraPipeline(activeCameraIndex)
        startHttpServer()
        startCloudFramePushLoop()
    }

    fun setCloudStreaming(enabled: Boolean) {
        cloudStreamingActive = enabled
        cloudStreamExpiresAtMs = if (enabled) {
            System.currentTimeMillis() + CLOUD_STREAM_AUTO_TIMEOUT_MS
        } else {
            0L
        }
        Log.i(TAG, "Render bulut canlı kamera akışı: ${if (enabled) "BAŞLATILDI" else "DURDURULDU"}")
    }

    fun switchCamera(index: Int) {
        activeCameraIndex = index.coerceIn(0, 3)
        closeCameraHardware()
        startCameraPipeline(activeCameraIndex)
    }

    fun getLatestSnapshot(): ByteArray {
        return latestJpeg ?: renderDiagnosticFrame(activeCameraIndex)
    }

    private fun startCloudFramePushLoop() {
        scope.launch {
            while (isActive) {
                if (cloudStreamingActive) {
                    if (System.currentTimeMillis() > cloudStreamExpiresAtMs) {
                        cloudStreamingActive = false
                    } else {
                        try {
                            val jpeg = getLatestSnapshot()
                            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                            onCloudFrameReady(activeCameraIndex, b64)
                        } catch (_: Exception) { }
                    }
                    delay(200L)
                } else {
                    delay(500L)
                }
            }
        }
    }

    private fun startCameraPipeline(index: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraHardwareActive = false
            return
        }
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            availableCameraIds = manager.cameraIdList.toList()
            if (availableCameraIds.isEmpty()) {
                cameraHardwareActive = false
                return
            }
            val camId = availableCameraIds[index.coerceIn(0, availableCameraIds.lastIndex)]

            cameraThread = HandlerThread("UlakCameraThread").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)

            imageReader = ImageReader.newInstance(STREAM_WIDTH, STREAM_HEIGHT, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        processCameraFrame(image)
                    } finally {
                        image.close()
                    }
                }, cameraHandler)
            }

            manager.openCamera(
                camId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        cameraHardwareActive = true
                        createCaptureSession(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cameraHardwareActive = false
                        camera.close()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        cameraHardwareActive = false
                        camera.close()
                    }
                },
                cameraHandler,
            )
        } catch (e: Exception) {
            cameraHardwareActive = false
            Log.w(TAG, "Kamera başlatılamadı (fallback devrede): ${e.message}")
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        val surface = imageReader?.surface ?: return
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                            session.setRepeatingRequest(req.build(), null, cameraHandler)
                        } catch (_: Exception) { }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cameraHardwareActive = false
                    }
                },
                cameraHandler,
            )
        } catch (_: Exception) {
            cameraHardwareActive = false
        }
    }

    private fun processCameraFrame(image: Image) {
        val yPlane = image.planes[0].buffer
        val yBytes = ByteArray(yPlane.remaining())
        yPlane.get(yBytes)

        if (isSentryEnabled()) {
            detectMotion(yBytes)
        }

        val nv21 = yuv420ToNv21(image, yBytes)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 58, out)
        latestJpeg = out.toByteArray()
    }

    private fun detectMotion(yBytes: ByteArray) {
        val step = (yBytes.size / 256).coerceAtLeast(1)
        val signature = ByteArray(256)
        var idx = 0
        var i = 0
        while (i < yBytes.size && idx < 256) {
            signature[idx++] = yBytes[i]
            i += step
        }
        val prev = prevLumaSignature
        prevLumaSignature = signature
        if (prev == null) return

        var totalDiff = 0.0
        for (k in 0 until 256) {
            totalDiff += abs((signature[k].toInt() and 0xFF) - (prev[k].toInt() and 0xFF))
        }
        val meanDiff = totalDiff / 256.0
        val now = System.currentTimeMillis()
        if (meanDiff >= MOTION_THRESHOLD && (now - lastMotionAlertMs) > MOTION_COOLDOWN_MS) {
            lastMotionAlertMs = now
            onMotionDetected(now)
        }
    }

    private fun yuv420ToNv21(image: Image, yBytes: ByteArray): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        val yLen = minOf(yBytes.size, width * height)
        System.arraycopy(yBytes, 0, nv21, 0, yLen)

        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val uBytes = ByteArray(uBuffer.remaining()).also { uBuffer.get(it) }
        val vBytes = ByteArray(vBuffer.remaining()).also { vBuffer.get(it) }

        val pixelStride = image.planes[1].pixelStride
        var outPos = width * height
        var uvIndex = 0
        while (outPos + 1 < nv21.size && uvIndex < uBytes.size && uvIndex < vBytes.size) {
            nv21[outPos++] = vBytes[uvIndex]
            nv21[outPos++] = uBytes[uvIndex]
            uvIndex += pixelStride
        }
        return nv21
    }

    private fun renderDiagnosticFrame(camIndex: Int): ByteArray {
        val bmp = Bitmap.createBitmap(STREAM_WIDTH, STREAM_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#060B14"))

        val borderPaint = Paint().apply {
            color = Color.parseColor("#10B981")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRect(12f, 12f, STREAM_WIDTH - 12f, STREAM_HEIGHT - 12f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
        }
        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            textSize = 17f
            typeface = Typeface.MONOSPACE
        }
        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val camLabel = when (camIndex) {
            0 -> "CAM-0 • ÖN / AVM GENİŞ"
            1 -> "CAM-1 • ARKA KAMERA"
            2 -> "CAM-2 • 360° AVM BÜTÜNLEŞİK"
            else -> "CAM-3 • İÇ KABİN"
        }

        canvas.drawText("ULAK v4.0 • CANLI KAMERA & RENDER AKIŞI", 32f, 64f, titlePaint)
        canvas.drawText(camLabel, 32f, 112f, infoPaint)
        canvas.drawText("Zaman: $timeText", 32f, 152f, infoPaint)
        canvas.drawText("Nöbetçi: ${if (isSentryEnabled()) "AKTİF" else "BEKLEMEDE"} | Bulut: ${if (cloudStreamingActive) "CANLI" else "LAN"}", 32f, 192f, infoPaint)
        canvas.drawText("Camera2 kaynağı bekleniyor (Diagnostik Canlı Kare)", 32f, 248f, infoPaint)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private fun startHttpServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(HTTP_PORT)
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleHttpClient(client) }
                }
            } catch (_: Exception) { }
        }
    }

    private suspend fun handleHttpClient(client: Socket) {
        activeClients.incrementAndGet()
        try {
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: "/"

            val expectedToken = config.authToken
            if (expectedToken.isNotBlank() && !path.contains("token=$expectedToken")) {
                val unauthorized = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 12\r\n\r\nUnauthorized"
                client.getOutputStream().write(unauthorized.toByteArray())
                client.getOutputStream().flush()
                return
            }

            val out = client.getOutputStream()
            if (path.startsWith("/snapshot")) {
                val jpeg = getLatestSnapshot()
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\nConnection: close\r\n\r\n"
                out.write(headers.toByteArray())
                out.write(jpeg)
                out.flush()
            } else {
                val boundary = "ulakframe"
                val headers =
                    "HTTP/1.1 200 OK\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n\r\n"
                out.write(headers.toByteArray())
                out.flush()

                while (currentCoroutineContext().isActive && !client.isClosed) {
                    val jpeg = getLatestSnapshot()
                    val partHeader =
                        "--$boundary\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${jpeg.size}\r\n\r\n"
                    out.write(partHeader.toByteArray())
                    out.write(jpeg)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    delay(150L)
                }
            }
        } catch (_: Exception) {
        } finally {
            activeClients.decrementAndGet()
            try { client.close() } catch (_: Exception) { }
        }
    }

    private fun closeCameraHardware() {
        cameraHardwareActive = false
        try { captureSession?.close() } catch (_: Exception) { }
        try { cameraDevice?.close() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        try { cameraThread?.quitSafely() } catch (_: Exception) { }
        captureSession = null
        cameraDevice = null
        imageReader = null
        cameraThread = null
    }

    fun stop() {
        cloudStreamingActive = false
        scope.cancel()
        try { serverSocket?.close() } catch (_: Exception) { }
        closeCameraHardware()
    }
}
