package com.example.albanianidverification

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.albanianidverification.databinding.ActivityFaceVerificationBinding
import com.example.albanianidverification.utils.LivenessDetector
import com.example.albanianidverification.verification.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Face Verification Activity with selectable verification engines.
 *
 * Flow:
 *   Step 1 → Liveness detection (blink, smile, turn)
 *   Step 2 → Select engine + capture selfie
 *   Step 3 → Show comparison results
 *
 * Available engines:
 *   - ML Kit (Legacy)          — original landmark/histogram approach
 *   - FaceNet TFLite           — 128-d embeddings, ~24 MB, offline
 *   - MobileFaceNet TFLite     — 192-d embeddings, ~5 MB, offline, fast
 *   - DeepFace Server          — Python server, FaceNet512, highest accuracy
 */
class FaceVerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceVerificationBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var chipFaceImage: ByteArray? = null
    private var capturedFaceBitmap: Bitmap? = null
    private var isFaceDetected = false

    // Liveness detection
    private lateinit var livenessDetector: LivenessDetector

    // Current step (1 = Liveness, 2 = Capture, 3 = Results)
    private var currentStep = 1

    // Verification result
    private var verificationResult: VerificationResult? = null

    // Engine selector data
    private val engineEntries = mutableListOf<Pair<EngineType, Boolean>>()

    companion object {
        private const val TAG = "FaceVerification"
        const val EXTRA_CHIP_FACE_IMAGE = "chip_face_image"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera()
        else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ===== LIFECYCLE =====

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

        // Initialise all verification engines
        VerificationEngineManager.init(this)

        // Liveness detector
        livenessDetector = LivenessDetector(
            onLivenessUpdate = { status -> updateLivenessStatus(status) },
            onLivenessComplete = { passed ->
                if (passed) onLivenessCheckPassed() else onLivenessCheckFailed()
            }
        )

        displayChipFaceImage()
        setupButtons()
        setupEngineSelector()
        showStep1Liveness()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        livenessDetector.cleanup()
        VerificationEngineManager.release()
    }

    // ===== SETUP =====

    private fun displayChipFaceImage() {
        chipFaceImage?.let { bytes ->
            try {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                binding.chipFaceImageView.setImageBitmap(bmp)
                binding.chipFaceResultView.setImageBitmap(bmp)
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying chip face image", e)
            }
        }
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener { finish() }

        binding.captureButton.setOnClickListener {
            if (isFaceDetected) captureAndCompareFace()
            else Toast.makeText(this, "Please ensure your face is visible", Toast.LENGTH_SHORT).show()
        }

        binding.retryButton.setOnClickListener { resetToStep1() }
        binding.cancelButton.setOnClickListener { finish() }

        binding.continueButton.setOnClickListener {
            Toast.makeText(this, "Ready to call API!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ===== ENGINE SELECTOR =====

    private fun setupEngineSelector() {
        engineEntries.clear()
        engineEntries.addAll(VerificationEngineManager.getAvailableEngines())

        val labels = engineEntries.map { (type, available) ->
            if (available) type.displayName
            else "${type.displayName} (model missing)"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.engineSpinner.adapter = adapter

        // Pre-select the current engine
        val currentIdx = engineEntries.indexOfFirst {
            it.first == VerificationEngineManager.getCurrentEngineType()
        }
        if (currentIdx >= 0) binding.engineSpinner.setSelection(currentIdx)

        binding.engineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val (type, available) = engineEntries[pos]
                if (!available) {
                    Toast.makeText(
                        this@FaceVerificationActivity,
                        "Model file missing! Place ${getModelFileName(type)} in app/src/main/assets/",
                        Toast.LENGTH_LONG
                    ).show()
                    // Revert to current engine
                    val revertIdx = engineEntries.indexOfFirst {
                        it.first == VerificationEngineManager.getCurrentEngineType()
                    }
                    if (revertIdx >= 0) binding.engineSpinner.setSelection(revertIdx)
                    return
                }

                VerificationEngineManager.setEngine(type)
                updateEngineDescription(type)

                // Show/hide DeepFace URL input
                binding.deepfaceUrlContainer.visibility =
                    if (type == EngineType.DEEPFACE_SERVER) View.VISIBLE else View.GONE

                Log.i(TAG, "Engine selected: ${type.displayName}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Initial description
        updateEngineDescription(VerificationEngineManager.getCurrentEngineType())
    }

    private fun updateEngineDescription(type: EngineType) {
        val desc = when (type) {
            EngineType.ML_KIT_LEGACY ->
                "Original approach: landmarks + histograms. No deep learning embeddings. Low accuracy for ID-to-selfie."
            EngineType.FACENET_TFLITE ->
                "Google FaceNet 128-d embeddings. 99.63% LFW. ~24 MB model. Fully offline."
            EngineType.MOBILE_FACENET_TFLITE ->
                "MobileFaceNet 192-d embeddings. 99.55% LFW. ~5 MB model. Very fast. Fully offline."
            EngineType.DEEPFACE_SERVER ->
                "Python server running FaceNet512. Highest accuracy. Requires WiFi to server."
        }
        binding.engineDescriptionText.text = desc
    }

    private fun getModelFileName(type: EngineType): String = when (type) {
        EngineType.FACENET_TFLITE -> "facenet.tflite"
        EngineType.MOBILE_FACENET_TFLITE -> "mobilefacenet.tflite"
        else -> ""
    }

    // ===== STEP 1: LIVENESS DETECTION =====

    private fun showStep1Liveness() {
        currentStep = 1
        binding.step1LivenessScreen.visibility = View.VISIBLE
        binding.step2CaptureScreen.visibility = View.GONE
        binding.step3ResultsScreen.visibility = View.GONE
        livenessDetector.reset()
    }

    private fun updateLivenessStatus(status: LivenessDetector.LivenessStatus) {
        runOnUiThread {
            binding.livenessInstructionText.text = status.instruction
            val progress = listOf(
                status.blinkDetected, status.smileDetected, status.headTurnDetected
            ).count { it }
            binding.livenessProgressBar.progress = progress
            binding.livenessCheckText.text = buildString {
                append(if (status.blinkDetected) "✓" else "○"); append(" Blink\n")
                append(if (status.smileDetected) "✓" else "○"); append(" Smile\n")
                append(if (status.headTurnDetected) "✓" else "○"); append(" Turn head")
            }
        }
    }

    private fun onLivenessCheckPassed() {
        runOnUiThread {
            binding.livenessInstructionText.text = "All checks passed!"
            binding.livenessInstructionCard.setCardBackgroundColor(0xE04CAF50.toInt())
            Toast.makeText(this, "✓ Liveness verified!", Toast.LENGTH_SHORT).show()
            binding.livenessInstructionCard.postDelayed({ showStep2Capture() }, 1500)
        }
    }

    private fun onLivenessCheckFailed() {
        runOnUiThread {
            binding.livenessInstructionText.text = "Liveness check failed"
            binding.livenessInstructionCard.setCardBackgroundColor(0xE0F44336.toInt())
            Toast.makeText(this, "Please try again", Toast.LENGTH_SHORT).show()
            binding.livenessInstructionCard.postDelayed({ resetToStep1() }, 2000)
        }
    }

    // ===== STEP 2: CAPTURE & COMPARE =====

    private fun showStep2Capture() {
        currentStep = 2
        binding.step1LivenessScreen.visibility = View.GONE
        binding.step2CaptureScreen.visibility = View.VISIBLE
        binding.step3ResultsScreen.visibility = View.GONE
        binding.statusText.text = "Position your face and capture"
        binding.captureButton.isEnabled = true
        rebindCameraToStep2()
    }

    private fun rebindCameraToStep2() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview2.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, FaceAnalyzerStep2()) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview, imageCapture, imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndCompareFace() {
        val ic = imageCapture ?: return

        // Apply DeepFace URL if selected
        if (VerificationEngineManager.getCurrentEngineType() == EngineType.DEEPFACE_SERVER) {
            val url = binding.deepfaceUrlInput.text?.toString()?.trim()
            if (!url.isNullOrEmpty()) {
                VerificationEngineManager
                    .getEngineInstance<DeepFaceServerEngine>(EngineType.DEEPFACE_SERVER)
                    ?.updateServerUrl(url)
            }
        }

        binding.captureButton.isEnabled = false
        binding.processingOverlay.visibility = View.VISIBLE
        binding.processingIndicator.visibility = View.VISIBLE

        val engineName = VerificationEngineManager.getEngine().displayName
        binding.processingText.text = "Comparing with $engineName..."

        ic.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                lifecycleScope.launch {
                    capturedFaceBitmap = bitmap
                    compareFaces(bitmap)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed", exception)
                runOnUiThread {
                    binding.processingOverlay.visibility = View.GONE
                    binding.processingIndicator.visibility = View.GONE
                    binding.captureButton.isEnabled = true
                    Toast.makeText(
                        this@FaceVerificationActivity,
                        "Capture failed: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    private suspend fun compareFaces(capturedBitmap: Bitmap) {
        runOnUiThread { binding.processingText.text = "Comparing faces..." }

        try {
            val chipBitmap = BitmapFactory.decodeByteArray(chipFaceImage, 0, chipFaceImage!!.size)
            if (chipBitmap == null) {
                showError("Failed to load reference image")
                return
            }

            // Use the selected verification engine
            val engine = VerificationEngineManager.getEngine()
            val result = engine.verify(chipBitmap, capturedBitmap)

            verificationResult = result

            Log.i(TAG, "Verification result: match=${result.isMatch}, " +
                    "sim=${result.similarity}, engine=${result.engineName}")

            runOnUiThread { showStep3Results() }

        } catch (e: Exception) {
            Log.e(TAG, "Face comparison failed", e)
            showError("Comparison failed: ${e.message}")
        }
    }

    // ===== STEP 3: RESULTS =====

    private fun showStep3Results() {
        currentStep = 3
        binding.step1LivenessScreen.visibility = View.GONE
        binding.step2CaptureScreen.visibility = View.GONE
        binding.step3ResultsScreen.visibility = View.VISIBLE

        val result = verificationResult ?: return

        // Display captured face
        binding.capturedFaceImageView.setImageBitmap(capturedFaceBitmap)

        // Display similarity score
        val percentage = (result.similarity * 100).toInt()
        binding.similarityText.text = "$percentage%"

        // Engine used
        binding.engineUsedText.text = "Engine: ${result.engineName}"

        if (result.isMatch) {
            // SUCCESS
            binding.resultText.text = "✓ VERIFICATION SUCCESSFUL"
            binding.resultText.setTextColor(0xFF2E7D32.toInt())
            binding.resultCard.setCardBackgroundColor(0xFFE8F5E9.toInt())
            binding.detailsText.text = buildString {
                append("Liveness Check: ✓ PASSED\n")
                append("Face Match: ✓ CONFIRMED\n")
                append("Match Score: $percentage%\n")
                append("Threshold: ${(result.threshold * 100).toInt()}%\n")
                append(result.details)
            }
            binding.detailsText.visibility = View.VISIBLE
            binding.resultButtonsContainer.visibility = View.GONE
            binding.continueButton.visibility = View.VISIBLE

        } else if (result.isBorderline) {
            // BORDERLINE
            binding.resultText.text = "⚠ BORDERLINE MATCH"
            binding.resultText.setTextColor(0xFFF57C00.toInt())
            binding.resultCard.setCardBackgroundColor(0xFFFFF3E0.toInt())
            binding.detailsText.text = buildString {
                append("Liveness Check: ✓ PASSED\n")
                append("Face Match: ⚠ BORDERLINE\n")
                append("Match Score: $percentage%\n")
                append("Required: ${(result.threshold * 100).toInt()}%\n")
                append(result.details)
                append("\n\nThe match is close but below threshold.\n")
                append("If you are the person on the ID, you can proceed manually.")
            }
            binding.detailsText.visibility = View.VISIBLE
            binding.resultButtonsContainer.visibility = View.VISIBLE
            binding.continueButton.visibility = View.VISIBLE
            binding.continueButton.text = "I Am This Person - Continue"

        } else {
            // FAILURE
            binding.resultText.text = "✗ VERIFICATION FAILED"
            binding.resultText.setTextColor(0xFFC62828.toInt())
            binding.resultCard.setCardBackgroundColor(0xFFFFEBEE.toInt())
            binding.detailsText.text = buildString {
                append("Liveness Check: ✓ PASSED\n")
                append("Face Match: ✗ FAILED\n")
                append("Match Score: $percentage%\n")
                append("Required: ${(result.threshold * 100).toInt()}%\n")
                append(result.details)
                append("\n\nThe faces do not match sufficiently.")
            }
            binding.detailsText.visibility = View.VISIBLE
            binding.resultButtonsContainer.visibility = View.VISIBLE
            binding.continueButton.visibility = View.GONE
        }
    }

    // ===== CAMERA =====

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, FaceAnalyzerStep1()) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview, imageCapture, imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private inner class FaceAnalyzerStep1 : ImageAnalysis.Analyzer {
        private val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            if (currentStep != 1) { imageProxy.close(); return }
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        isFaceDetected = faces.isNotEmpty()
                        runOnUiThread {
                            binding.faceDetectionIndicator.setColorFilter(
                                getColor(
                                    if (isFaceDetected) android.R.color.holo_green_dark
                                    else android.R.color.holo_red_dark
                                )
                            )
                            if (faces.isNotEmpty()) livenessDetector.processFace(faces[0])
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else imageProxy.close()
        }
    }

    private inner class FaceAnalyzerStep2 : ImageAnalysis.Analyzer {
        private val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            if (currentStep != 2) { imageProxy.close(); return }
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        isFaceDetected = faces.isNotEmpty()
                        runOnUiThread {
                            binding.faceDetectionIndicator.setColorFilter(
                                getColor(
                                    if (isFaceDetected) android.R.color.holo_green_dark
                                    else android.R.color.holo_red_dark
                                )
                            )
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else imageProxy.close()
        }
    }

    // ===== UTILITIES =====

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val matrix = Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        matrix.postScale(-1f, 1f) // Mirror for front camera
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun showError(message: String) {
        runOnUiThread {
            binding.processingOverlay.visibility = View.GONE
            binding.processingIndicator.visibility = View.GONE
            binding.captureButton.isEnabled = true
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetToStep1() {
        livenessDetector.reset()
        capturedFaceBitmap = null
        verificationResult = null
        isFaceDetected = false
        showStep1Liveness()
        startCamera()
    }
}