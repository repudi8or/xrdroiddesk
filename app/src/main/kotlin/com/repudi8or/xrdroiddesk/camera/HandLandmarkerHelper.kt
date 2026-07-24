package com.repudi8or.xrdroiddesk.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.repudi8or.xrdroiddesk.gesture.HandData

class HandLandmarkerHelper(
    private val context: Context,
    private val onHandData: (HandData) -> Unit,
) {
    private val landmarker: HandLandmarker
    private lateinit var h264Decoder: H264FrameDecoder
    private val frameCount =
        java.util.concurrent.atomic
            .AtomicLong(0)
    private val decodedCount =
        java.util.concurrent.atomic
            .AtomicLong(0)

    init {
        val baseOptions =
            BaseOptions
                .builder()
                .setModelAssetPath(MODEL_ASSET)
                .build()
        val options =
            HandLandmarker.HandLandmarkerOptions
                .builder()
                .setBaseOptions(baseOptions)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ -> onHandData(result.toHandData()) }
                .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
        h264Decoder =
            H264FrameDecoder { bitmap ->
                val count = decodedCount.incrementAndGet()
                val corrected = rotateBitmap(bitmap)
                maybeSaveFrame(corrected, count)
                val mpImage = BitmapImageBuilder(corrected).build()
                landmarker.detectAsync(mpImage, System.currentTimeMillis())
            }
    }

    fun processFrame(
        frameBytes: ByteArray,
        @Suppress("UNUSED_PARAMETER") timestampMs: Long,
    ) {
        val count = frameCount.incrementAndGet()
        if (count == 1L || count % 100L == 0L) {
            val b0 = if (frameBytes.isNotEmpty()) "%02x".format(frameBytes[0].toInt() and 0xFF) else "??"
            val b1 = if (frameBytes.size > 1) "%02x".format(frameBytes[1].toInt() and 0xFF) else "??"
            Log.i(TAG, "processFrame #$count — ${frameBytes.size}B magic=$b0$b1")
        }
        h264Decoder.submit(frameBytes)
    }

    fun close() {
        decodedCount.set(0)
        landmarker.close()
        h264Decoder.close()
    }

    private fun rotateBitmap(bitmap: Bitmap): Bitmap = bitmap

    private fun maybeSaveFrame(
        bitmap: Bitmap,
        decodedCount: Long,
    ) {
        val saveAt = setOf(1L, 100L, 300L, 600L, 1000L)
        if (decodedCount !in saveAt) return
        try {
            val dir =
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                    ?: context.filesDir
            val file = java.io.File(dir, "xr_frame_$decodedCount.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Log.i(TAG, "saved frame #$decodedCount → ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        } catch (e: Exception) {
            Log.w(TAG, "maybeSaveFrame #$decodedCount failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HandLandmarker"
        private const val MODEL_ASSET = "hand_landmarker.task"
    }
}

private fun HandLandmarkerResult.toHandData(): HandData {
    val tracked = landmarks().isNotEmpty()
    if (tracked) {
        val pinch =
            landmarkToHandData(
                true,
                worldLandmarks()[0].map { Triple(it.x(), it.y(), it.z()) },
                landmarks()[0].map { Triple(it.x(), it.y(), it.z()) },
            ).pinchStrength
        Log.d("HandLandmarker", "hand detected — pinch=%.2f".format(pinch))
    }
    if (!tracked) return HandData(isTracked = false, pinchStrength = 0f, pointerPose = null)

    val imageList = landmarks()[0].map { Triple(it.x(), it.y(), it.z()) }
    val worldList = worldLandmarks()[0].map { Triple(it.x(), it.y(), it.z()) }
    return landmarkToHandData(isTracked = true, worldLandmarks = worldList, imageLandmarks = imageList)
}
