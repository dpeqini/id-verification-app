package com.example.albanianidverification.verification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * TFLite-based face verification engine.
 *
 * Pipeline:
 *   1. ML Kit detects the face → bounding box + eye landmarks
 *   2. Crop + align the face using eye positions
 *   3. Resize to the model's input size
 *   4. Per-image standardisation (FaceNet) or simple normalisation (MobileFaceNet)
 *   5. Run TFLite interpreter → 128-d or 512-d embedding
 *   6. Compare embeddings with cosine similarity
 */
class TFLiteVerificationEngine private constructor(
    private val interpreter: Interpreter,
    private val config: ModelConfig
) : FaceVerificationEngine {

    override val displayName: String = config.displayName
    override val description: String = config.description

    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )

    // ---- public API -------------------------------------------------------

    override suspend fun verify(
        referenceFace: Bitmap,
        capturedFace: Bitmap
    ): VerificationResult = withContext(Dispatchers.Default) {

        val start = System.currentTimeMillis()

        val refEmbedding = extractEmbedding(referenceFace)
        val capEmbedding = extractEmbedding(capturedFace)

        if (refEmbedding == null || capEmbedding == null) {
            return@withContext VerificationResult(
                similarity = 0f,
                isMatch = false,
                isBorderline = false,
                threshold = config.matchThreshold,
                borderlineThreshold = config.borderlineThreshold,
                engineName = config.displayName,
                details = "Could not detect/align face in one or both images."
            )
        }

        val cosSim = cosineSimilarity(refEmbedding, capEmbedding)
        val l2Dist = l2Distance(refEmbedding, capEmbedding)
        val elapsed = System.currentTimeMillis() - start

        Log.d(TAG, "${config.displayName}  cosSim=$cosSim  l2=$l2Dist  time=${elapsed}ms")

        VerificationResult(
            similarity = cosSim,
            isMatch = cosSim >= config.matchThreshold,
            isBorderline = cosSim >= config.borderlineThreshold && cosSim < config.matchThreshold,
            threshold = config.matchThreshold,
            borderlineThreshold = config.borderlineThreshold,
            engineName = config.displayName,
            details = "Cos: %.4f  •  L2: %.4f  •  ${elapsed}ms".format(cosSim, l2Dist)
        )
    }

    override fun release() {
        interpreter.close()
        faceDetector.close()
    }

    // ---- embedding extraction ---------------------------------------------

    private suspend fun extractEmbedding(bitmap: Bitmap): FloatArray? {
        val face = detectFace(bitmap) ?: return null

        // Get eye landmarks for alignment
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

        // Crop face with generous padding
        val box = face.boundingBox
        val padX = (box.width() * 0.3f).toInt()
        val padY = (box.height() * 0.3f).toInt()
        val cropRect = Rect(
            max(0, box.left - padX),
            max(0, box.top - padY),
            min(bitmap.width, box.right + padX),
            min(bitmap.height, box.bottom + padY)
        )

        var cropped = Bitmap.createBitmap(
            bitmap, cropRect.left, cropRect.top,
            cropRect.width(), cropRect.height()
        )

        // Align face using eye positions
        if (leftEye != null && rightEye != null) {
            cropped = alignFace(cropped, leftEye, rightEye, cropRect)
        }

        // Resize to model input size
        val resized = Bitmap.createScaledBitmap(
            cropped, config.inputSize, config.inputSize, true
        )

        // Convert to greyscale if required by model
        val prepared = if (config.useGrayscale) toGrayscale(resized) else resized

        // Build input tensor
        val inputBuffer = when (config.preprocessMode) {
            PreprocessMode.FACENET_STANDARDISE -> facenetStandardise(prepared)
            PreprocessMode.NORMALIZE_MINUS1_PLUS1 -> normaliseMinus1Plus1(prepared)
            PreprocessMode.NORMALIZE_0_1 -> normalise01(prepared)
        }

        // Run inference
        val output = Array(1) { FloatArray(config.embeddingDim) }
        interpreter.run(inputBuffer, output)

        // L2-normalise the embedding for stable cosine similarity
        val emb = output[0]
        val norm = sqrt(emb.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        if (norm > 0f) for (i in emb.indices) emb[i] /= norm

        return emb
    }

    // ---- face detection (ML Kit) ------------------------------------------

    private suspend fun detectFace(bitmap: Bitmap): com.google.mlkit.vision.face.Face? {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faces = faceDetector.process(inputImage).await()
        if (faces.isEmpty()) {
            Log.w(TAG, "No face detected")
            return null
        }
        // Pick largest face if multiple
        return faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
    }

    // ---- alignment --------------------------------------------------------

    private fun alignFace(
        faceBitmap: Bitmap,
        leftEye: PointF,
        rightEye: PointF,
        cropRect: Rect
    ): Bitmap {
        // Translate eye coords to crop-relative
        val lx = leftEye.x - cropRect.left
        val ly = leftEye.y - cropRect.top
        val rx = rightEye.x - cropRect.left
        val ry = rightEye.y - cropRect.top

        val angle = Math.toDegrees(
            kotlin.math.atan2((ry - ly).toDouble(), (rx - lx).toDouble())
        ).toFloat()

        val cx = faceBitmap.width / 2f
        val cy = faceBitmap.height / 2f

        val matrix = Matrix()
        matrix.postRotate(-angle, cx, cy)

        return try {
            Bitmap.createBitmap(
                faceBitmap, 0, 0,
                faceBitmap.width, faceBitmap.height,
                matrix, true
            )
        } catch (e: Exception) {
            faceBitmap // fallback to unaligned
        }
    }

    // ---- preprocessing ----------------------------------------------------

    private fun facenetStandardise(bitmap: Bitmap): ByteBuffer {
        val sz = config.inputSize
        val buffer = ByteBuffer.allocateDirect(4 * sz * sz * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(sz * sz)
        bitmap.getPixels(pixels, 0, sz, 0, 0, sz, sz)

        // Collect all channel values
        val floats = FloatArray(sz * sz * 3)
        var idx = 0
        for (px in pixels) {
            floats[idx++] = ((px shr 16) and 0xFF).toFloat()
            floats[idx++] = ((px shr 8) and 0xFF).toFloat()
            floats[idx++] = (px and 0xFF).toFloat()
        }

        // Per-image standardisation: x' = (x - mean) / max(std, 1/√n)
        val mean = floats.average().toFloat()
        val variance = floats.sumOf { ((it - mean).toDouble()).pow(2) } / floats.size
        val std = max(sqrt(variance).toFloat(), 1f / sqrt(floats.size.toFloat()))

        for (f in floats) {
            buffer.putFloat((f - mean) / std)
        }
        buffer.rewind()
        return buffer
    }

    private fun normaliseMinus1Plus1(bitmap: Bitmap): ByteBuffer {
        val sz = config.inputSize
        val buffer = ByteBuffer.allocateDirect(4 * sz * sz * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(sz * sz)
        bitmap.getPixels(pixels, 0, sz, 0, 0, sz, sz)

        for (px in pixels) {
            buffer.putFloat(((px shr 16) and 0xFF) / 127.5f - 1f)
            buffer.putFloat(((px shr 8) and 0xFF) / 127.5f - 1f)
            buffer.putFloat((px and 0xFF) / 127.5f - 1f)
        }
        buffer.rewind()
        return buffer
    }

    private fun normalise01(bitmap: Bitmap): ByteBuffer {
        val sz = config.inputSize
        val buffer = ByteBuffer.allocateDirect(4 * sz * sz * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(sz * sz)
        bitmap.getPixels(pixels, 0, sz, 0, 0, sz, sz)

        for (px in pixels) {
            buffer.putFloat(((px shr 16) and 0xFF) / 255f)
            buffer.putFloat(((px shr 8) and 0xFF) / 255f)
            buffer.putFloat((px and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val cm = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmp
    }

    // ---- similarity metrics -----------------------------------------------

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var nA = 0f; var nB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            nA += a[i] * a[i]
            nB += b[i] * b[i]
        }
        val denom = sqrt(nA) * sqrt(nB)
        return if (denom > 0f) (dot / denom).coerceIn(-1f, 1f) else 0f
    }

    private fun l2Distance(a: FloatArray, b: FloatArray): Float {
        return sqrt(a.indices.sumOf { i -> ((a[i] - b[i]).toDouble()).pow(2) }.toFloat())
    }

    // ---- suspend helper ---------------------------------------------------

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { cont.resume(it, null) }
            addOnFailureListener { cont.resumeWith(Result.failure(it)) }
        }

    // ---- factory ----------------------------------------------------------

    companion object {
        private const val TAG = "TFLiteEngine"

        /**
         * Create a FaceNet-128 engine.
         * Expects `facenet.tflite` in assets (160×160 input → 128-d).
         */
        fun createFaceNet(context: Context): TFLiteVerificationEngine {
            val config = ModelConfig(
                assetFileName = "facenet.tflite",
                displayName = "FaceNet TFLite",
                description = "128-d embeddings • 99.63% LFW • ~24 MB",
                inputSize = 160,
                embeddingDim = 128,
                preprocessMode = PreprocessMode.FACENET_STANDARDISE,
                matchThreshold = 0.40f,
                borderlineThreshold = 0.30f,
                useGrayscale = false
            )
            return create(context, config)
        }

        /**
         * Create a MobileFaceNet engine.
         * Expects `mobilefacenet.tflite` in assets (112×112 input → 192-d).
         */
        fun createMobileFaceNet(context: Context): TFLiteVerificationEngine {
            val config = ModelConfig(
                assetFileName = "mobilefacenet.tflite",
                displayName = "MobileFaceNet TFLite",
                description = "192-d embeddings • 99.55% LFW • ~5 MB • fast",
                inputSize = 112,
                embeddingDim = 192,
                preprocessMode = PreprocessMode.NORMALIZE_MINUS1_PLUS1,
                matchThreshold = 0.45f,
                borderlineThreshold = 0.35f,
                useGrayscale = false
            )
            return create(context, config)
        }

        private fun create(context: Context, config: ModelConfig): TFLiteVerificationEngine {
            val model = loadModelFile(context, config.assetFileName)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val interpreter = Interpreter(model, options)

            // Log model input/output shapes
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            Log.i(TAG, "${config.displayName} loaded — " +
                    "input: ${inputTensor.shape().toList()}  " +
                    "output: ${outputTensor.shape().toList()}")

            // Auto-detect embedding dimension from model output
            val actualEmbDim = outputTensor.shape().last()
            val adjustedConfig = if (actualEmbDim != config.embeddingDim) {
                Log.w(TAG, "Model output dim ($actualEmbDim) differs from config " +
                        "(${config.embeddingDim}). Auto-adjusting.")
                config.copy(embeddingDim = actualEmbDim)
            } else config

            return TFLiteVerificationEngine(interpreter, adjustedConfig)
        }

        private fun loadModelFile(context: Context, fileName: String): MappedByteBuffer {
            val fd = context.assets.openFd(fileName)
            val inputStream = FileInputStream(fd.fileDescriptor)
            val fileChannel = inputStream.channel
            return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
        }

        /** Check whether a given .tflite asset exists. */
        fun isModelAvailable(context: Context, fileName: String): Boolean {
            return try {
                context.assets.openFd(fileName).close()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    // ---- configuration data class -----------------------------------------

    data class ModelConfig(
        val assetFileName: String,
        val displayName: String,
        val description: String,
        val inputSize: Int,
        val embeddingDim: Int,
        val preprocessMode: PreprocessMode,
        val matchThreshold: Float,
        val borderlineThreshold: Float,
        val useGrayscale: Boolean
    )

    enum class PreprocessMode {
        /** FaceNet per-image standardisation: (x - mean) / std */
        FACENET_STANDARDISE,
        /** Normalise to [-1, +1]: x / 127.5 - 1 */
        NORMALIZE_MINUS1_PLUS1,
        /** Normalise to [0, 1]: x / 255 */
        NORMALIZE_0_1
    }
}