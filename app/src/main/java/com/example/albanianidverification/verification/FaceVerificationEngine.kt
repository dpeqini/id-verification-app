package com.example.albanianidverification.verification

import android.graphics.Bitmap

/**
 * Common interface for all face verification engines.
 * Each engine takes two face bitmaps and returns a verification result.
 */
interface FaceVerificationEngine {

    /** Human-readable name shown in the UI selector. */
    val displayName: String

    /** Short description shown below the name in the UI. */
    val description: String

    /**
     * Compare two face images and return a [VerificationResult].
     *
     * @param referenceFace  The high-res face from the ID card chip
     * @param capturedFace   The selfie captured by the front camera
     */
    suspend fun verify(referenceFace: Bitmap, capturedFace: Bitmap): VerificationResult

    /** Release any resources (interpreters, detectors). */
    fun release()
}

/**
 * Unified result returned by every engine.
 */
data class VerificationResult(
    /** Similarity score normalised to 0.0 – 1.0. */
    val similarity: Float,
    /** True when similarity ≥ the engine's match threshold. */
    val isMatch: Boolean,
    /** True when similarity is in the borderline zone. */
    val isBorderline: Boolean,
    /** The engine's match threshold (for display). */
    val threshold: Float,
    /** The engine's borderline threshold (for display). */
    val borderlineThreshold: Float,
    /** Name of the engine that produced this result. */
    val engineName: String,
    /** Extra info (timing, distance metric, etc.). */
    val details: String = ""
)

/**
 * Enum of every available engine so the UI can enumerate them.
 */
enum class EngineType(val displayName: String) {
    ML_KIT_LEGACY("ML Kit (Legacy)"),
    FACENET_TFLITE("FaceNet TFLite"),
    MOBILE_FACENET_TFLITE("MobileFaceNet TFLite"),
    DEEPFACE_SERVER("DeepFace Server")
}