package com.example.albanianidverification.verification

import android.content.Context
import android.util.Log

/**
 * Central manager that holds all available [FaceVerificationEngine] instances.
 *
 * Call [init] once from the Activity's onCreate, then use [getEngine] and
 * [getAvailableEngines] to drive the UI selector.
 */
object VerificationEngineManager {

    private const val TAG = "EngineManager"

    private val engines = mutableMapOf<EngineType, FaceVerificationEngine>()
    private val availability = mutableMapOf<EngineType, Boolean>()
    private var currentEngine: EngineType = EngineType.ML_KIT_LEGACY

    /**
     * Initialise all engines. Call once from the Activity.
     * Engines whose .tflite model is missing are simply marked unavailable.
     */
    fun init(context: Context) {
        // 1 – ML Kit Legacy (always available)
        engines[EngineType.ML_KIT_LEGACY] = MLKitLegacyEngine()
        availability[EngineType.ML_KIT_LEGACY] = true
        Log.i(TAG, "ML Kit Legacy engine: AVAILABLE")

        // 2 – FaceNet TFLite
        if (TFLiteVerificationEngine.isModelAvailable(context, "facenet.tflite")) {
            try {
                engines[EngineType.FACENET_TFLITE] =
                    TFLiteVerificationEngine.createFaceNet(context)
                availability[EngineType.FACENET_TFLITE] = true
                Log.i(TAG, "FaceNet TFLite engine: AVAILABLE")
            } catch (e: Exception) {
                Log.e(TAG, "FaceNet TFLite init failed", e)
                availability[EngineType.FACENET_TFLITE] = false
            }
        } else {
            availability[EngineType.FACENET_TFLITE] = false
            Log.w(TAG, "FaceNet TFLite engine: NOT AVAILABLE (facenet.tflite missing)")
        }

        // 3 – MobileFaceNet TFLite
        if (TFLiteVerificationEngine.isModelAvailable(context, "mobilefacenet.tflite")) {
            try {
                engines[EngineType.MOBILE_FACENET_TFLITE] =
                    TFLiteVerificationEngine.createMobileFaceNet(context)
                availability[EngineType.MOBILE_FACENET_TFLITE] = true
                Log.i(TAG, "MobileFaceNet TFLite engine: AVAILABLE")
            } catch (e: Exception) {
                Log.e(TAG, "MobileFaceNet TFLite init failed", e)
                availability[EngineType.MOBILE_FACENET_TFLITE] = false
            }
        } else {
            availability[EngineType.MOBILE_FACENET_TFLITE] = false
            Log.w(TAG, "MobileFaceNet engine: NOT AVAILABLE (mobilefacenet.tflite missing)")
        }

        // 4 – DeepFace Server (always "available" as an option; may fail at runtime)
        engines[EngineType.DEEPFACE_SERVER] = DeepFaceServerEngine()
        availability[EngineType.DEEPFACE_SERVER] = true
        Log.i(TAG, "DeepFace Server engine: AVAILABLE (requires running server)")

        // Default to best available engine
        currentEngine = when {
            availability[EngineType.MOBILE_FACENET_TFLITE] == true -> EngineType.MOBILE_FACENET_TFLITE
            availability[EngineType.FACENET_TFLITE] == true -> EngineType.FACENET_TFLITE
            else -> EngineType.ML_KIT_LEGACY
        }

        Log.i(TAG, "Default engine: ${currentEngine.displayName}")
    }

    /** Get the currently selected engine. */
    fun getEngine(): FaceVerificationEngine {
        return engines[currentEngine] ?: engines[EngineType.ML_KIT_LEGACY]!!
    }

    /** Get the currently selected engine type. */
    fun getCurrentEngineType(): EngineType = currentEngine

    /** Set the active engine. */
    fun setEngine(type: EngineType) {
        if (availability[type] == true) {
            currentEngine = type
            Log.i(TAG, "Switched to engine: ${type.displayName}")
        } else {
            Log.w(TAG, "Cannot switch to ${type.displayName}: not available")
        }
    }

    /** All engine types with their availability status. */
    fun getAvailableEngines(): List<Pair<EngineType, Boolean>> {
        return EngineType.entries.map { it to (availability[it] == true) }
    }

    /** Get a specific engine instance (e.g. to configure DeepFace URL). */
    @Suppress("UNCHECKED_CAST")
    fun <T : FaceVerificationEngine> getEngineInstance(type: EngineType): T? {
        return engines[type] as? T
    }

    /** Release all engines. Call from Activity.onDestroy. */
    fun release() {
        engines.values.forEach { it.release() }
        engines.clear()
        availability.clear()
    }
}