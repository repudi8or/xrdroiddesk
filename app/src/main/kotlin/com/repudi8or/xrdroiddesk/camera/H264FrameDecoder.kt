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
    private var srcWidth = 640
    private var srcHeight = 480

    // Lock-free producer-consumer between the USB frame loop (submit) and the
    // codec callback thread (onInputBufferAvailable).
    // Invariant: exactly one of {pendingFrame, parkedBufferIdx} holds a value at a time.
    private val pendingFrame = AtomicReference<ByteArray?>(null)
    private val parkedBufferIdx = AtomicInteger(-1)

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
                    val bitmap = image?.toScaledBitmap(srcWidth, srcHeight, DST_W, DST_H)
                    image?.close()
                    mc.releaseOutputBuffer(index, false)
                    if (bitmap != null && !closed) onBitmap(bitmap)
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
        configuring = true
        Thread(::configureAndStart, "HevcInit").start()
    }

    fun submit(bytes: ByteArray) {
        if (closed) return
        if (!isAnnexB(bytes)) {
            warmUp()
            return
        }
        if (!running) {
            warmUp()
            return
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

    private fun isAnnexB(bytes: ByteArray): Boolean =
        bytes.size >= 5 &&
            bytes[0] == 0.toByte() &&
            bytes[1] == 0.toByte() &&
            bytes[2] == 0.toByte() &&
            bytes[3] == 1.toByte()

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

    companion object {
        private const val TAG = "H264FrameDecoder"
        private const val DST_W = 640
        private const val DST_H = 480
    }
}

// Subsample a YUV_420_888 Image directly to dstW×dstH, avoiding a full-resolution JPEG encode.
private fun android.media.Image.toScaledBitmap(
    srcW: Int,
    srcH: Int,
    dstW: Int,
    dstH: Int,
): Bitmap? {
    val xStep = srcW / dstW
    val yStep = srcH / dstH

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

    // UV plane — VU interleaved for NV21; UV is half resolution in both dimensions
    var uvDst = dstW * dstH
    for (row in 0 until dstH / 2) {
        val srcRow = row * yStep
        for (col in 0 until dstW / 2) {
            val idx = srcRow * uvStride + col * xStep * uvPixelStride
            if (idx < vBytes.size) nv21[uvDst++] = vBytes[idx]
            if (idx < uBytes.size) nv21[uvDst++] = uBytes[idx]
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
