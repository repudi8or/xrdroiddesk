package com.repudi8or.xrdroiddesk.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Decodes an HEVC (H.265) Annex-B stream from the XReal camera and delivers scaled bitmaps.
// MediaCodec runs in async mode on a dedicated HandlerThread; the frame loop never blocks.
internal class H264FrameDecoder(
    private val onBitmap: (Bitmap) -> Unit,
) : AutoCloseable {
    private val callbackThread = HandlerThread("HevcCodec").also { it.start() }
    private val callbackHandler = Handler(callbackThread.looper)

    private val codec: MediaCodec = MediaCodec.createDecoderByType("video/hevc")

    @Volatile private var running = false

    @Volatile private var configuring = false

    @Volatile private var closed = false
    private val droppedCount =
        java.util.concurrent.atomic
            .AtomicLong(0)
    private var srcWidth = 640
    private var srcHeight = 480

    // Lock-free producer-consumer between the USB frame loop (submit) and the
    // codec callback thread (onInputBufferAvailable).
    // Invariant: exactly one of {pendingFrame, parkedBufferIdx} holds a value at a time.
    private val pendingFrame = AtomicReference<ByteArray?>(null)
    private val parkedBufferIdx = AtomicInteger(-1)

    // IDR gating: drop frames until we see an IDR NALU so the decoder always starts clean.
    @Volatile private var syncNeeded = true

    private val firstFrameLogged =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    // Input timestamps must be strictly increasing; output frames are for live tracking
    // so we just use a monotonic counter in µs.
    private val inputTs = AtomicLong(0L)

    private val callback =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(
                mc: MediaCodec,
                index: Int,
            ) {
                val bytes = pendingFrame.getAndSet(null)
                if (bytes != null) {
                    submitToBuffer(mc, index, bytes)
                } else {
                    parkedBufferIdx.set(index)
                }
            }

            override fun onOutputBufferAvailable(
                mc: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                if (info.size > 0) {
                    val image = mc.getOutputImage(index)
                    if (image != null && firstFrameLogged.compareAndSet(false, true)) {
                        val y = image.planes[0]
                        val uv = image.planes[1]
                        Log.i(
                            TAG,
                            "FRAME_DIMS: ${image.width}x${image.height} " +
                                "yStride=${y.rowStride} uvStride=${uv.rowStride} uvPixel=${uv.pixelStride} " +
                                "yBuf=${y.buffer.remaining()} uvBuf=${uv.buffer.remaining()}",
                        )
                    }
                    val bitmap = image?.toScaledBitmap(DST_W, DST_H)
                    image?.close()
                    mc.releaseOutputBuffer(index, false)
                    if (bitmap != null && !closed) {
                        Log.d(TAG, "bitmap → MediaPipe ${bitmap.width}x${bitmap.height}")
                        onBitmap(bitmap)
                    }
                } else {
                    mc.releaseOutputBuffer(index, false)
                }
            }

            override fun onError(
                mc: MediaCodec,
                e: MediaCodec.CodecException,
            ) {
                Log.w(TAG, "codec error: ${e.message}")
                if (!closed) {
                    running = false
                    configuring = false
                    warmUp()
                }
            }

            override fun onOutputFormatChanged(
                mc: MediaCodec,
                format: MediaFormat,
            ) {
                srcWidth = format.getInteger(MediaFormat.KEY_WIDTH, srcWidth)
                srcHeight = format.getInteger(MediaFormat.KEY_HEIGHT, srcHeight)
                Log.i(TAG, "HEVC output format: ${srcWidth}x$srcHeight → decoded as ${DST_W}x${DST_H}")
            }
        }

    fun warmUp() {
        if (running || configuring || closed) return
        syncNeeded = true
        firstFrameLogged.set(false)
        configuring = true
        Thread(::configureAndStart, "HevcInit").start()
    }

    fun submit(bytes: ByteArray) {
        if (closed) return
        if (!isAnnexB(bytes)) {
            val n = droppedCount.incrementAndGet()
            if (n == 1L || n % 100L == 0L) {
                val magic = bytes.take(4).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
                Log.w(TAG, "non-Annex-B frame #$n dropped — magic: $magic (camera may be MJPEG, not HEVC)")
            }
            warmUp()
            return
        }
        if (!running) {
            warmUp()
            return
        }
        if (syncNeeded) {
            when {
                containsNaluOfType(bytes, 19, 20) -> {
                    syncNeeded = false
                    Log.i(TAG, "IDR found — sync acquired (${bytes.size}B)")
                }
                containsNaluOfType(bytes, 32, 33, 34) -> { /* VPS/SPS/PPS — pass through, still waiting for IDR */ }
                else -> {
                    Log.d(TAG, "pre-IDR P-frame dropped (${bytes.size}B)")
                    return
                }
            }
        }
        val idx = parkedBufferIdx.getAndSet(-1)
        if (idx >= 0) {
            submitToBuffer(codec, idx, bytes)
        } else {
            pendingFrame.set(bytes)
        }
    }

    private fun submitToBuffer(
        mc: MediaCodec,
        index: Int,
        bytes: ByteArray,
    ) {
        try {
            val buf = mc.getInputBuffer(index) ?: return
            buf.clear()
            val len = minOf(bytes.size, buf.remaining())
            buf.put(bytes, 0, len)
            mc.queueInputBuffer(index, 0, len, inputTs.getAndAdd(16_667L), 0)
        } catch (e: Exception) {
            Log.w(TAG, "submitToBuffer: ${e.message}")
        }
    }

    private fun configureAndStart() {
        try {
            try {
                codec.reset()
            } catch (_: Exception) {
            }
            codec.setCallback(callback, callbackHandler)
            val format = MediaFormat.createVideoFormat("video/hevc", srcWidth, srcHeight)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 524_288)
            codec.configure(format, null, null, 0)
            codec.start()
            if (!closed) {
                running = true
                Log.i(TAG, "HEVC decoder running")
            } else {
                try {
                    codec.stop()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "configureAndStart failed: ${e.message}")
        } finally {
            configuring = false
        }
    }

    companion object {
        private const val TAG = "H264FrameDecoder"
        private const val DST_W = 640
        private const val DST_H = 480

        internal fun isAnnexB(bytes: ByteArray): Boolean =
            bytes.size >= 5 &&
                bytes[0] == 0.toByte() &&
                bytes[1] == 0.toByte() &&
                bytes[2] == 0.toByte() &&
                bytes[3] == 1.toByte()

        // Scans Annex-B NALUs and returns true if any NALU type matches one of the given types.
        // HEVC NAL types: IDR_W_RADL=19, IDR_N_LP=20, VPS=32, SPS=33, PPS=34
        internal fun containsNaluOfType(
            bytes: ByteArray,
            vararg types: Int,
        ): Boolean {
            var i = 0
            while (i < bytes.size - 3) {
                val is4Byte =
                    bytes[i] == 0.toByte() &&
                        bytes[i + 1] == 0.toByte() &&
                        bytes[i + 2] == 0.toByte() &&
                        bytes[i + 3] == 1.toByte()
                val is3Byte =
                    !is4Byte &&
                        bytes[i] == 0.toByte() &&
                        bytes[i + 1] == 0.toByte() &&
                        bytes[i + 2] == 1.toByte()
                when {
                    is4Byte && i + 4 < bytes.size -> {
                        val naluType = (bytes[i + 4].toInt() ushr 1) and 0x3F
                        if (naluType in types) return true
                        i += 4
                    }
                    is3Byte && i + 3 < bytes.size -> {
                        val naluType = (bytes[i + 3].toInt() ushr 1) and 0x3F
                        if (naluType in types) return true
                        i += 3
                    }
                    else -> i++
                }
            }
            return false
        }
    }

    override fun close() {
        closed = true
        running = false
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
        callbackThread.quitSafely()
    }
}

// Subsample a YUV_420_888 Image directly to dstW×dstH, avoiding a full-resolution JPEG encode.
// Uses image.width/height so the step is correct even when the decoder outputs a larger
// coded frame than the configured hint (e.g. 2048x1512 from XReal One Pro).
private fun android.media.Image.toScaledBitmap(
    dstW: Int,
    dstH: Int,
): Bitmap? {
    val xStep = width / dstW
    val yStep = height / dstH

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yStride = yPlane.rowStride
    val uvStride = vPlane.rowStride
    val uvPixelStride = vPlane.pixelStride

    val yBuf = yPlane.buffer
    val yBytes = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer
    val uBytes = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
    val vBytes = ByteArray(vBuf.remaining()).also { vBuf.get(it) }

    val nv21 = ByteArray(dstW * dstH + 2 * ((dstW + 1) / 2) * ((dstH + 1) / 2))

    // Y plane — sample every xStep columns and yStep rows
    var dst = 0
    for (row in 0 until dstH) {
        val srcRow = row * yStep
        for (col in 0 until dstW) {
            nv21[dst++] = yBytes[srcRow * yStride + col * xStep]
        }
    }

    // UV plane — VU interleaved for NV21; UV is half resolution in both dimensions.
    // UV source row = srcRow / 2 since UV has half the vertical resolution of Y.
    var uvDst = dstW * dstH
    for (row in 0 until dstH / 2) {
        val uvSrcRow = row * yStep
        for (col in 0 until dstW / 2) {
            val idx = uvSrcRow * uvStride + col * xStep * uvPixelStride
            nv21[uvDst++] = if (idx < vBytes.size) vBytes[idx] else 128.toByte()
            nv21[uvDst++] = if (idx < uBytes.size) uBytes[idx] else 128.toByte()
        }
    }

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, dstW, dstH, null)
    val out = ByteArrayOutputStream()
    return if (yuvImage.compressToJpeg(Rect(0, 0, dstW, dstH), 80, out)) {
        val jpeg = out.toByteArray()
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    } else {
        null
    }
}
