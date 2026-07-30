@file:Suppress("UNUSED_PARAMETER", "unused")
package com.google.mlkit.vision.face
class FaceLandmark {
    fun getPosition(): android.graphics.PointF = android.graphics.PointF(0f, 0f)
    companion object {
        const val MOUTH_BOTTOM = 0
        const val LEFT_EYE = 4
        const val MOUTH_LEFT = 5
        const val NOSE_BASE = 6
        const val RIGHT_EYE = 10
        const val MOUTH_RIGHT = 11
    }
}
class Face {
    fun getBoundingBox(): android.graphics.Rect = android.graphics.Rect()
    fun getLandmark(type: Int): FaceLandmark? = null
    fun getHeadEulerAngleX(): Float = 0f
    fun getHeadEulerAngleY(): Float = 0f
    fun getHeadEulerAngleZ(): Float = 0f
    fun getLeftEyeOpenProbability(): Float? = null
    fun getRightEyeOpenProbability(): Float? = null
    fun getTrackingId(): Int? = null
}
class FaceDetectorOptions {
    class Builder {
        fun setPerformanceMode(m: Int): Builder = this
        fun setLandmarkMode(m: Int): Builder = this
        fun setClassificationMode(m: Int): Builder = this
        fun setContourMode(m: Int): Builder = this
        fun setMinFaceSize(f: Float): Builder = this
        fun enableTracking(): Builder = this
        fun build(): FaceDetectorOptions = FaceDetectorOptions()
    }
    companion object {
        const val PERFORMANCE_MODE_FAST = 1
        const val PERFORMANCE_MODE_ACCURATE = 2
        const val LANDMARK_MODE_NONE = 1
        const val LANDMARK_MODE_ALL = 2
        const val CLASSIFICATION_MODE_NONE = 1
        const val CLASSIFICATION_MODE_ALL = 2
        const val CONTOUR_MODE_NONE = 1
    }
}
interface FaceDetector : java.io.Closeable {
    fun process(image: com.google.mlkit.vision.common.InputImage): com.google.android.gms.tasks.Task<MutableList<Face>>
}
object FaceDetection { @JvmStatic fun getClient(options: FaceDetectorOptions): FaceDetector = throw UnsupportedOperationException() }
