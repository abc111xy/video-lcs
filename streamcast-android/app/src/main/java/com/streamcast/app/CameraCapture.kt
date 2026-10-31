package com.streamcast.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat

/**
 * 摄像头画面提供者。
 *
 * 与屏幕采集（VirtualDisplay）互斥：两者都产出 YUV 帧再交给编码器，
 * 上层只需关心「帧从哪来」，编码 / 推流逻辑完全复用。
 *
 * 用 camera2 + ImageReader(YUV_420_888)：不依赖 CameraX（少装一个库、APK 更小），
 * 且能精确拿到每一帧，便于做帧率节流。
 */
class CameraCapture(
    private val context: Context,
    private val handler: Handler,
    private val onFrame: (yuv: ByteArray, width: Int, height: Int) -> Unit,
    private val onError: (String) -> Unit
) {

    private var thread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    private var width = 0
    private var height = 0
    /** 预览输出面（TextureView 的 SurfaceTexture）；空则表示只推流 */
    private var previewSurface: Surface? = null
    private var minIntervalMs = 66L
    private var lastFrameAt = 0L
    /** 前置摄像头画面是镜像的，推流前需要翻转回真实方向 */
    private var mirror = false

    @Volatile
    var running = false
        private set

    var isFront: Boolean = false
        private set

    fun cameraCount(): Int = try {
        (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.size
    } catch (e: Exception) {
        0
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * 打开摄像头。
     * @param front true 为前置（自拍 / 直播主播），false 为后置
     * @param targetWidth 期望宽度，实际取最接近的可用尺寸（不超过期望值）
     * @param previewSurface 预览输出面；为 null 时只推流不预览（后台直播）
     */
    fun start(front: Boolean, targetWidth: Int, fps: Int, previewSurface: Surface? = null) {
        stop()
        this.previewSurface = previewSurface
        if (!hasPermission()) {
            onError("未授予摄像头权限")
            return
        }
        val manager = try {
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        } catch (e: Exception) {
            onError("无法访问摄像头服务")
            return
        }
        val id = try {
            pickCameraId(manager, front)
        } catch (e: Exception) {
            null
        }
        if (id == null) {
            onError(if (front) "设备没有前置摄像头" else "设备没有可用摄像头")
            return
        }
        isFront = front

        val t = HandlerThread("streamcast-camera")
        t.start()
        thread = t
        val h = Handler(t.looper)
        cameraHandler = h
        minIntervalMs = (1000L / fps.coerceIn(1, 60)).coerceAtLeast(16L)

        try {
            val chars = manager.getCameraCharacteristics(id)
            mirror = chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            val size = chooseSize(chars, targetWidth)
            width = size.width
            height = size.height
            open(manager, id, h)
        } catch (e: CameraAccessException) {
            onError("摄像头打开失败：${e.reason}")
            stop()
        } catch (e: Exception) {
            onError("摄像头打开失败：${e.message ?: e.javaClass.simpleName}")
            stop()
        }
    }

    private fun open(manager: CameraManager, id: String, h: Handler) {
        val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ ir -> handleImage(ir) }, h)
        reader = r
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                device = null
                onError("摄像头已被其他应用占用")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                device = null
                onError("摄像头异常（code=$error）")
            }
        }, h)
    }

    private fun createSession(camera: CameraDevice) {
        val surface = reader?.surface ?: return
        // 预览与推流共用同一个相机会话：一次采集，两路输出，避免两次打开相机
        val outputs = ArrayList<Surface>(2)
        outputs.add(surface)
        previewSurface?.let { if (it.isValid) outputs.add(it) }
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            req.addTarget(surface)
                            previewSurface?.let { if (it.isValid) req.addTarget(it) }
                            req.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            s.setRepeatingRequest(req.build(), null, cameraHandler)
                            running = true
                        } catch (e: Exception) {
                            onError("启动预览失败：${e.message ?: e.javaClass.simpleName}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        onError("摄像头配置失败")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            onError("摄像头配置失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 取帧：YUV_420_888 三平面拼成 I420，直接喂给编码器输入缓冲 */
    private fun handleImage(ir: ImageReader) {
        val image = try {
            ir.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            if (!running) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFrameAt < minIntervalMs) return
            lastFrameAt = now

            val w = image.width
            val h = image.height
            val yuv = toI420(image)
            if (yuv != null) onFrame(yuv, w, h)
        } catch (e: Exception) {
            // 单帧失败不影响后续
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun toI420(image: android.media.Image): ByteArray? {
        val w = image.width
        val h = image.height
        val planes = image.planes
        if (planes.size < 3) return null
        val out = ByteArray(w * h * 3 / 2)

        // Y 平面：逐行拷贝，去掉行尾 padding
        val yPlane = planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var offset = 0
        if (yRowStride == w) {
            yBuf.get(out, 0, w * h)
            offset = w * h
        } else {
            val row = ByteArray(yRowStride)
            for (r in 0 until h) {
                yBuf.position(r * yRowStride)
                val len = minOf(yRowStride, yBuf.remaining())
                yBuf.get(row, 0, len)
                System.arraycopy(row, 0, out, offset, w)
                offset += w
            }
        }

        // U/V 平面：带 pixelStride 抽样（多数设备为 2），逐像素取值最稳
        offset = copyChroma(planes[1], w, h, out, offset, mirror)
        copyChroma(planes[2], w, h, out, offset, mirror)

        return out
    }

    private fun copyChroma(
        plane: android.media.Image.Plane,
        w: Int,
        h: Int,
        out: ByteArray,
        start: Int,
        mirror: Boolean
    ): Int {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val cw = w / 2
        val ch = h / 2
        var offset = start
        val base = buf.position()
        for (r in 0 until ch) {
            // 部分设备色度平面行数为奇数，越界就停，剩余补 0
            if (r >= (buf.limit() - base) / rowStride + 1) break
            for (c in 0 until cw) {
                val idx = base + r * rowStride + c * pixelStride
                if (idx >= buf.limit()) {
                    offset += cw - c
                    break
                }
                val dst = if (mirror) offset + (cw - 1 - c) else offset + c
                out[dst] = buf.get(idx)
            }
            offset += cw
        }
        return offset
    }

    private fun pickCameraId(manager: CameraManager, front: Boolean): String? {
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        var fallback: String? = null
        for (id in manager.cameraIdList) {
            val facing = try {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
            } catch (e: Exception) {
                null
            }
            if (facing == want) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    /** 选尺寸：优先不超过 targetWidth 的最大值，其次取最接近的 */
    private fun chooseSize(chars: CameraCharacteristics, targetWidth: Int): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(640, 480)
        val sorted = sizes.sortedByDescending { it.width.toLong() * it.height }
        val within = sorted.filter { it.width <= targetWidth }
        if (within.isNotEmpty()) return within.first()
        return sorted.last()
    }

    fun stop() {
        running = false
        try {
            session?.stopRepeating()
            session?.close()
        } catch (e: Exception) {
            // ignore
        }
        session = null
        previewSurface = null
        try {
            device?.close()
        } catch (e: Exception) {
            // ignore
        }
        device = null
        try {
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
        } catch (e: Exception) {
            // ignore
        }
        reader = null
        cameraHandler = null
        try {
            thread?.quitSafely()
        } catch (e: Exception) {
            // ignore
        }
        thread = null
    }

    /** 当前相机输出尺寸（用于界面显示与接收端 meta） */
    fun outputSize(): Pair<Int, Int> = width to height

    /** 前置镜像：部分设备 YUV 输出本身已镜像，这里只在推流侧翻转一次 */
    fun shouldMirror(): Boolean = mirror && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
}
