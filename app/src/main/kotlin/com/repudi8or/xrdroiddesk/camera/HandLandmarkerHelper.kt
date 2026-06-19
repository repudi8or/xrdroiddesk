package com.repudi8or.xrdroiddesk.camera

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.repudi8or.xrdroiddesk.gesture.HandData

class HandLandmarkerHelper(
    context: Context,
    private val onHandData: (HandData) -> Unit,
) {
    private val landmarker: HandLandmarker
    private lateinit var h264Decoder: H264FrameDecoder

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
                val mpImage = BitmapImageBuilder(bitmap).build()
                landmarker.detectAsync(mpImage, System.currentTimeMillis())
            }
    }

    fun processFrame(
        frameBytes: ByteArray,
        @Suppress("UNUSED_PARAMETER") timestampMs: Long,
    ) {
        h264Decoder.submit(frameBytes)
    }

    fun close() {
        landmarker.close()
        h264Decoder.close()
    }

    companion object {
        private const val TAG = "HandLandmarker"
        private const val MODEL_ASSET = "hand_landmarker.task"
    }
}

private fun HandLandmarkerResult.toHandData(): HandData {
    val tracked = landmarks().isNotEmpty()
    if (tracked) {
        Log.i(
            "HandLandmarker",
            "hand detected — pinch=${landmarkToHandData(
                true,
                worldLandmarks()[0].map { Triple(it.x(), it.y(), it.z()) },
                landmarks()[0].map { Triple(it.x(), it.y(), it.z()) },
            ).pinchStrength}",
        )
    }
    if (!tracked) return HandData(isTracked = false, pinchStrength = 0f, pointerPose = null)

    val imageList = landmarks()[0].map { Triple(it.x(), it.y(), it.z()) }
    val worldList = worldLandmarks()[0].map { Triple(it.x(), it.y(), it.z()) }
    return landmarkToHandData(isTracked = true, worldLandmarks = worldList, imageLandmarks = imageList)
}
