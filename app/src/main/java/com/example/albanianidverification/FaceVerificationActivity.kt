package com.example.albanianidverification

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.albanianidverification.databinding.ActivityFaceVerificationBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class FaceVerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceVerificationBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var chipFaceImage: ByteArray? = null
    private var capturedFaceBitmap: Bitmap? = null
    private var isFaceDetected = false

    companion object {
        private const val TAG = "FaceVerification"
        const val EXTRA_CHIP_FACE_IMAGE = "chip_face_image"
        private const val SIMILARITY_THRESHOLD = 0.75f // 75% similarity required
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required for face verification", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chipFaceImage = intent.getByteArrayExtra(EXTRA_CHIP_FACE_IMAGE)

        if (chipFaceImage == null) {
            Toast.makeText(this, "No reference face image provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Display the chip face image
        displayChipFaceImage()

        // Set up button listeners
        setupButtons()

        // Request camera permission
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.instructionText.text = "Position your face in the frame and tap 'Capture Face'"
    }

    private fun displayChipFaceImage() {
        chipFaceImage?.let { imageBytes ->
            try {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                binding.chipFaceImageView.setImageBitmap(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying chip face image", e)
            }
        }
    }

    private fun setupButtons() {
        binding.captureButton.setOnClickListener {
            if (isFaceDetected) {
                captureAndCompareFace()
            } else {
                Toast.makeText(this, "Please ensure your face is clearly visible", Toast.LENGTH_SHORT).show()
            }
        }

        binding.retryButton.setOnClickListener {
            resetVerification()
        }

        binding.retryButton.visibility = android.view.View.GONE
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalyzer
                )

            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_LONG).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndCompareFace() {
        val imageCapture = imageCapture ?: return

        binding.captureButton.isEnabled = false
        binding.statusText.text = "Capturing..."

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()

                    lifecycleScope.launch {
                        capturedFaceBitmap = bitmap
                        displayCapturedFace(bitmap)
                        compareFaces(bitmap)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    runOnUiThread {
                        binding.statusText.text = "Capture failed"
                        binding.captureButton.isEnabled = true
                        Toast.makeText(
                            this@FaceVerificationActivity,
                            "Failed to capture image: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun displayCapturedFace(bitmap: Bitmap) {
        runOnUiThread {
            binding.capturedFaceImageView.setImageBitmap(bitmap)
            binding.capturedFaceImageView.visibility = android.view.View.VISIBLE
        }
    }

    private suspend fun compareFaces(capturedBitmap: Bitmap) {
        binding.statusText.text = "Comparing faces..."
        binding.progressBar.visibility = android.view.View.VISIBLE

        try {
            // Get chip face bitmap
            val chipBitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeByteArray(chipFaceImage, 0, chipFaceImage!!.size)
            }

            if (chipBitmap == null) {
                showError("Failed to load reference image")
                return
            }

            // Perform face comparison
            val similarity = withContext(Dispatchers.Default) {
                calculateFaceSimilarity(chipBitmap, capturedBitmap)
            }

            runOnUiThread {
                binding.progressBar.visibility = android.view.View.GONE
                displayResults(similarity)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Face comparison failed", e)
            showError("Comparison failed: ${e.message}")
        }
    }

    private suspend fun calculateFaceSimilarity(bitmap1: Bitmap, bitmap2: Bitmap): Float {
        return withContext(Dispatchers.Default) {
            try {
                // Use ML Kit for face detection and feature extraction
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .build()

                val detector = FaceDetection.getClient(options)

                // Detect faces in both images
                val image1 = InputImage.fromBitmap(bitmap1, 0)
                val image2 = InputImage.fromBitmap(bitmap2, 0)

                var similarity = 0f

                // Process both images
                val task1 = detector.process(image1)
                val task2 = detector.process(image2)

                // Wait for both to complete
                val faces1 = task1.await()
                val faces2 = task2.await()

                Log.d(TAG, "Faces detected - Chip: ${faces1.size}, Captured: ${faces2.size}")

                if (faces1.isEmpty() || faces2.isEmpty()) {
                    Log.w(TAG, "No face detected in one or both images")
                    return@withContext 0f
                }

                // Compare the first face in each image
                val face1 = faces1[0]
                val face2 = faces2[0]

                // Calculate similarity based on multiple factors
                var matchScore = 0f
                var factorCount = 0

                // 1. Compare face bounds (size similarity)
                val sizeRatio1 = face1.boundingBox.width().toFloat() / bitmap1.width
                val sizeRatio2 = face2.boundingBox.width().toFloat() / bitmap2.width
                val sizeSimilarity = 1f - kotlin.math.abs(sizeRatio1 - sizeRatio2)
                matchScore += sizeSimilarity * 0.2f // 20% weight
                factorCount++

                Log.d(TAG, "Size similarity: $sizeSimilarity")

                // 2. Compare smile probability (if available)
                face1.smilingProbability?.let { smile1 ->
                    face2.smilingProbability?.let { smile2 ->
                        val smileSimilarity = 1f - kotlin.math.abs(smile1 - smile2)
                        matchScore += smileSimilarity * 0.1f // 10% weight
                        factorCount++
                        Log.d(TAG, "Smile similarity: $smileSimilarity")
                    }
                }

                // 3. Compare eye open probability
                face1.leftEyeOpenProbability?.let { leftEye1 ->
                    face2.leftEyeOpenProbability?.let { leftEye2 ->
                        val eyeSimilarity = 1f - kotlin.math.abs(leftEye1 - leftEye2)
                        matchScore += eyeSimilarity * 0.1f // 10% weight
                        factorCount++
                        Log.d(TAG, "Left eye similarity: $eyeSimilarity")
                    }
                }

                // 4. Compare head pose (yaw, roll, pitch)
                val yawSimilarity = 1f - (kotlin.math.abs(face1.headEulerAngleY - face2.headEulerAngleY) / 180f)
                val pitchSimilarity = 1f - (kotlin.math.abs(face1.headEulerAngleX - face2.headEulerAngleX) / 180f)
                val rollSimilarity = 1f - (kotlin.math.abs(face1.headEulerAngleZ - face2.headEulerAngleZ) / 180f)

                matchScore += (yawSimilarity + pitchSimilarity + rollSimilarity) / 3f * 0.2f // 20% weight
                factorCount++

                Log.d(TAG, "Pose similarity: yaw=$yawSimilarity, pitch=$pitchSimilarity, roll=$rollSimilarity")

                // 5. Compare pixel similarity in face regions (simple histogram comparison)
                val pixelSimilarity = compareHistograms(
                    cropFace(bitmap1, face1.boundingBox),
                    cropFace(bitmap2, face2.boundingBox)
                )
                matchScore += pixelSimilarity * 0.4f // 40% weight
                factorCount++

                Log.d(TAG, "Pixel similarity: $pixelSimilarity")

                similarity = matchScore.coerceIn(0f, 1f)

                Log.d(TAG, "Final similarity score: $similarity")

                detector.close()

                similarity

            } catch (e: Exception) {
                Log.e(TAG, "Error calculating similarity", e)
                0f
            }
        }
    }

    private fun cropFace(bitmap: Bitmap, bounds: android.graphics.Rect): Bitmap {
        // Add some padding around the face
        val padding = 20
        val left = (bounds.left - padding).coerceAtLeast(0)
        val top = (bounds.top - padding).coerceAtLeast(0)
        val width = (bounds.width() + padding * 2).coerceAtMost(bitmap.width - left)
        val height = (bounds.height() + padding * 2).coerceAtMost(bitmap.height - top)

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun compareHistograms(bitmap1: Bitmap, bitmap2: Bitmap): Float {
        // Resize both to same size for fair comparison
        val size = 100
        val resized1 = Bitmap.createScaledBitmap(bitmap1, size, size, true)
        val resized2 = Bitmap.createScaledBitmap(bitmap2, size, size, true)

        // Calculate simple color histograms
        val hist1 = IntArray(256 * 3) // RGB histograms
        val hist2 = IntArray(256 * 3)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val pixel1 = resized1.getPixel(x, y)
                val pixel2 = resized2.getPixel(x, y)

                hist1[android.graphics.Color.red(pixel1)]++
                hist1[256 + android.graphics.Color.green(pixel1)]++
                hist1[512 + android.graphics.Color.blue(pixel1)]++

                hist2[android.graphics.Color.red(pixel2)]++
                hist2[256 + android.graphics.Color.green(pixel2)]++
                hist2[512 + android.graphics.Color.blue(pixel2)]++
            }
        }

        // Calculate correlation between histograms
        var correlation = 0.0
        var sum1 = 0.0
        var sum2 = 0.0

        for (i in hist1.indices) {
            correlation += hist1[i] * hist2[i]
            sum1 += hist1[i] * hist1[i]
            sum2 += hist2[i] * hist2[i]
        }

        val denominator = sqrt(sum1 * sum2)

        return if (denominator > 0) {
            (correlation / denominator).toFloat()
        } else {
            0f
        }
    }

    private fun displayResults(similarity: Float) {
        val percentage = (similarity * 100).toInt()
        val isMatch = similarity >= SIMILARITY_THRESHOLD

        binding.resultCard.visibility = android.view.View.VISIBLE
        binding.similarityText.text = "Similarity: $percentage%"

        if (isMatch) {
            binding.resultText.text = "✓ VERIFICATION SUCCESSFUL"
            binding.resultText.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.resultCard.setCardBackgroundColor(getColor(android.R.color.holo_green_light))
            binding.statusText.text = "Identity verified successfully!"

            // Show success message
            Toast.makeText(this, "Face verification successful!", Toast.LENGTH_LONG).show()

        } else {
            binding.resultText.text = "✗ VERIFICATION FAILED"
            binding.resultText.setTextColor(getColor(android.R.color.holo_red_dark))
            binding.resultCard.setCardBackgroundColor(getColor(android.R.color.holo_red_light))
            binding.statusText.text = "Faces do not match sufficiently"

            // Show retry button
            binding.retryButton.visibility = android.view.View.VISIBLE
        }

        binding.captureButton.isEnabled = false

        // Add detailed breakdown
        binding.detailsText.text = buildString {
            append("Match threshold: ${(SIMILARITY_THRESHOLD * 100).toInt()}%\n")
            append("Calculated similarity: $percentage%\n")
            append("Result: ${if (isMatch) "MATCH" else "NO MATCH"}\n")
        }
        binding.detailsText.visibility = android.view.View.VISIBLE
    }

    private fun showError(message: String) {
        runOnUiThread {
            binding.progressBar.visibility = android.view.View.GONE
            binding.statusText.text = message
            binding.captureButton.isEnabled = true
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetVerification() {
        binding.resultCard.visibility = android.view.View.GONE
        binding.detailsText.visibility = android.view.View.GONE
        binding.capturedFaceImageView.visibility = android.view.View.GONE
        binding.captureButton.isEnabled = true
        binding.retryButton.visibility = android.view.View.GONE
        binding.statusText.text = "Position your face in the frame"
        capturedFaceBitmap = null
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Rotate bitmap if needed (front camera is often mirrored)
        val matrix = Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        matrix.postScale(-1f, 1f) // Mirror horizontally for front camera

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // Face analyzer for real-time face detection
    private inner class FaceAnalyzer : ImageAnalysis.Analyzer {
        private val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )

        @OptIn(ExperimentalGetImage::class) override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                detector.process(image)
                    .addOnSuccessListener { faces ->
                        isFaceDetected = faces.isNotEmpty()
                        runOnUiThread {
                            if (isFaceDetected) {
                                binding.faceDetectionIndicator.setColorFilter(
                                    getColor(android.R.color.holo_green_dark)
                                )
                                binding.captureButton.isEnabled = true
                            } else {
                                binding.faceDetectionIndicator.setColorFilter(
                                    getColor(android.R.color.holo_red_dark)
                                )
                            }
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

// Extension function for awaiting ML Kit tasks
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            cont.resume(result, null)
        }
        addOnFailureListener { exception ->
            cont.resumeWith(Result.failure(exception))
        }
    }
}