package com.example.albanianidverification

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.albanianidverification.api.IdCardAuthRequest
import com.example.albanianidverification.databinding.ActivityFaceVerificationBinding
import com.example.albanianidverification.security.ApiClient
import com.example.albanianidverification.security.TokenManager
import com.example.albanianidverification.utils.DateUtils
import com.example.albanianidverification.utils.LivenessDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Face Verification Activity
 *
 * 3-step flow:
 *   Step 1 → ML Kit liveness detection (blink / smile / head-turn)
 *   Step 2 → Capture live selfie
 *   Step 3 → POST to /api/v1/auth/id-card and display result
 *
 * All face comparison is performed server-side by the Python DeepFace service.
 * No local face matching or engine selection is involved.
 */
class FaceVerificationActivity : AppCompatActivity() {

    // ── Binding & camera ─────────────────────────────────────────────────────
    private lateinit var binding: ActivityFaceVerificationBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    // ── Chip data received from NFCReadActivity ───────────────────────────────
    private var chipFaceBytes:  ByteArray? = null
    private var nationalId:     String     = ""
    private var holderName:     String     = ""
    private var dateOfBirth:    String     = ""
    private var placeOfBirth:    String     = ""
    private var expiryDate:     String     = ""

    // ── State ─────────────────────────────────────────────────────────────────
    private var currentStep       = 1
    private var isFaceDetected    = false
    private var capturedBitmap:   Bitmap?  = null

    // ── Liveness ──────────────────────────────────────────────────────────────
    private lateinit var livenessDetector: LivenessDetector

    companion object {
        private const val TAG = "FaceVerification"

        /** ByteArray — raw JPEG/JPEG2000 bytes of the chip face image from DG2 */
        const val EXTRA_CHIP_FACE_BYTES   = "chip_face_bytes"
        const val EXTRA_NATIONAL_ID       = "national_id"
        const val EXTRA_NAME              = "holder_name"
        const val EXTRA_DATE_OF_BIRTH     = "date_of_birth"
        const val EXTRA_EXPIRY_DATE       = "expiry_date"
        const val EXTRA_PLACE_OF_BIRTH      = "placeOfBirth"
    }

    // ── Permission launcher ───────────────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Read extras from NFCReadActivity
        chipFaceBytes = intent.getByteArrayExtra(EXTRA_CHIP_FACE_BYTES)
        nationalId    = intent.getStringExtra(EXTRA_NATIONAL_ID)   ?: ""
        holderName    = intent.getStringExtra(EXTRA_NAME)          ?: ""
        dateOfBirth   = intent.getStringExtra(EXTRA_DATE_OF_BIRTH) ?: ""
        expiryDate    = intent.getStringExtra(EXTRA_EXPIRY_DATE)   ?: ""
        placeOfBirth    = intent.getStringExtra(EXTRA_PLACE_OF_BIRTH)   ?: ""

        if (chipFaceBytes == null) {
            Toast.makeText(this, "No chip face image provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        livenessDetector = LivenessDetector(
            onLivenessUpdate   = { status -> updateLivenessStatus(status) },
            onLivenessComplete = { passed ->
                if (passed) onLivenessCheckPassed() else onLivenessCheckFailed()
            }
        )

        displayChipFaceImage()
        setupButtons()
        showStep1Liveness()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        livenessDetector.cleanup()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Setup
    // ══════════════════════════════════════════════════════════════════════════

    private fun displayChipFaceImage() {
        chipFaceBytes?.let { bytes ->
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
            if (isFaceDetected) captureAndAuthenticate()
            else Toast.makeText(this, "Please ensure your face is visible", Toast.LENGTH_SHORT).show()
        }

        binding.retryButton.setOnClickListener   { resetToStep1() }
        binding.cancelButton.setOnClickListener  { finish() }
        binding.continueButton.setOnClickListener {
            // TODO: Navigate to the voting screen
            Toast.makeText(this, "Proceeding to vote…", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 1 — Liveness Detection
    // ══════════════════════════════════════════════════════════════════════════

    private fun showStep1Liveness() {
        currentStep = 1
        binding.step1LivenessScreen.visibility  = View.VISIBLE
        binding.step2CaptureScreen.visibility   = View.GONE
        binding.step3ResultsScreen.visibility   = View.GONE
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
                append(if (status.blinkDetected)    "✓" else "○"); append(" Blink\n")
                append(if (status.smileDetected)    "✓" else "○"); append(" Smile\n")
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

    // ══════════════════════════════════════════════════════════════════════════
    // Step 2 — Capture Selfie
    // ══════════════════════════════════════════════════════════════════════════

    private fun showStep2Capture() {
        currentStep = 2
        binding.step1LivenessScreen.visibility  = View.GONE
        binding.step2CaptureScreen.visibility   = View.VISIBLE
        binding.step3ResultsScreen.visibility   = View.GONE
        binding.statusText.text = "Position your face and capture"
        binding.captureButton.isEnabled = true
        rebindCameraToStep2()
    }

    private fun rebindCameraToStep2() {
        ProcessCameraProvider.getInstance(this).addListener({
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
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
                Log.e(TAG, "Camera binding failed (step 2)", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Capture the selfie, then build the [IdCardAuthRequest] and call the backend.
     */
    private fun captureAndAuthenticate() {
        val ic = imageCapture ?: return
        binding.captureButton.isEnabled = false
        binding.processingOverlay.visibility   = View.VISIBLE
        binding.processingIndicator.visibility = View.VISIBLE
        binding.processingText.text = "Capturing photo…"

        ic.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = imageProxyToBitmap(image)
                image.close()
                capturedBitmap = bmp
                runOnUiThread { binding.processingText.text = "Authenticating with server…" }
                lifecycleScope.launch { authenticateWithBackend(bmp) }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exception)
                runOnUiThread {
                    binding.processingOverlay.visibility   = View.GONE
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

    // ══════════════════════════════════════════════════════════════════════════
    // Backend authentication
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun authenticateWithBackend(selfieBitmap: Bitmap) {
        try {
            val chipBase64   = Base64.encodeToString(chipFaceBytes, Base64.NO_WRAP)
            val selfieBase64 = bitmapToBase64(selfieBitmap)

            val request = IdCardAuthRequest(
                nationalId       = nationalId,
                name             = holderName,
                dateOfBirth      = DateUtils.parseMrzDate(dateOfBirth, true),
                expiryDate       = DateUtils.parseMrzDate(expiryDate, false),
                chipFacePhoto    = chipBase64,
                liveSelfie       = selfieBase64,
                municipality     = placeOfBirth,
                livenessConfirmed = true        // liveness passed in Step 1
            )

            Log.i(TAG, "Sending auth request for nationalId=$nationalId")

            val response = ApiClient.votingService.authenticateWithIdCard(request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.accessToken != null) {
                    // Persist tokens
                    TokenManager.saveTokens(
                        accessToken  = body.accessToken,
                        refreshToken = body.refreshToken ?: "",
                        expiresInSeconds = body.expiresIn ?: 1800L
                    )
                    Log.i(TAG, "Authentication successful — voterId=${body.voterId}")
                    runOnUiThread { showAuthSuccess(body.voterId ?: "—") }
                } else {
                    // HTTP 200 but body says failure (shouldn't normally happen)
                    runOnUiThread { showAuthFailure(response.code(), body?.message) }
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.w(TAG, "Auth failed — HTTP ${response.code()}: $errorBody")
                runOnUiThread { showAuthFailure(response.code(), errorBody) }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Network error during authentication", e)
            runOnUiThread {
                binding.processingOverlay.visibility   = View.GONE
                binding.processingIndicator.visibility = View.GONE
                binding.captureButton.isEnabled = true
                Toast.makeText(
                    this,
                    "Connection failed — check your internet connection",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 3 — Results
    // ══════════════════════════════════════════════════════════════════════════

    private fun showAuthSuccess(voterId: String) {
        currentStep = 3
        binding.processingOverlay.visibility   = View.GONE
        binding.processingIndicator.visibility = View.GONE
        binding.step1LivenessScreen.visibility = View.GONE
        binding.step2CaptureScreen.visibility  = View.GONE
        binding.step3ResultsScreen.visibility  = View.VISIBLE

        capturedBitmap?.let { binding.capturedFaceImageView.setImageBitmap(it) }

        binding.resultText.text = "✓ AUTHENTICATION SUCCESSFUL"
        binding.resultText.setTextColor(0xFF2E7D32.toInt())
        binding.resultCard.setCardBackgroundColor(0xFFE8F5E9.toInt())

        binding.similarityText.text = "✓"
        binding.engineUsedText.text = "Voter ID: $voterId"

        binding.detailsText.text = buildString {
            append("Liveness Check: ✓ PASSED\n")
            append("Face Match: ✓ CONFIRMED (server)\n")
            append("Identity: ✓ VERIFIED\n")
            append("Voter ID: $voterId")
        }
        binding.detailsText.visibility = View.VISIBLE

        binding.resultButtonsContainer.visibility = View.GONE
        binding.continueButton.visibility = View.VISIBLE
        binding.continueButton.text = "Proceed to Vote"
    }

    private fun showAuthFailure(httpCode: Int, rawError: String?) {
        currentStep = 3
        binding.processingOverlay.visibility   = View.GONE
        binding.processingIndicator.visibility = View.GONE
        binding.step1LivenessScreen.visibility = View.GONE
        binding.step2CaptureScreen.visibility  = View.GONE
        binding.step3ResultsScreen.visibility  = View.VISIBLE

        capturedBitmap?.let { binding.capturedFaceImageView.setImageBitmap(it) }

        val (title, detail) = when (httpCode) {
            400  -> "✗ INVALID DATA"         to "Liveness confirmation failed, card may be expired, or chip data is invalid."
            401  -> "✗ FACE DOES NOT MATCH"  to "The live selfie does not match the photo stored in the ID chip."
            429  -> "⚠ TOO MANY ATTEMPTS"    to "You have been rate-limited. Please try again in 15 minutes."
            503  -> "⚠ SERVICE UNAVAILABLE"  to "The face verification service is temporarily down. Please try again shortly."
            else -> "✗ AUTHENTICATION FAILED" to (rawError?.take(200) ?: "An unexpected error occurred (HTTP $httpCode).")
        }

        binding.resultText.text = title
        binding.resultText.setTextColor(0xFFC62828.toInt())
        binding.resultCard.setCardBackgroundColor(0xFFFFEBEE.toInt())
        binding.similarityText.text = "✗"
        binding.engineUsedText.text = "HTTP $httpCode"
        binding.detailsText.text    = detail
        binding.detailsText.visibility = View.VISIBLE

        binding.resultButtonsContainer.visibility = View.VISIBLE
        binding.continueButton.visibility         = View.GONE
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Camera — Step 1 analyzer
    // ══════════════════════════════════════════════════════════════════════════

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
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
                Log.e(TAG, "Camera binding failed (step 1)", e)
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
                                getColor(if (isFaceDetected) android.R.color.holo_green_dark
                                else android.R.color.holo_red_dark)
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
                                getColor(if (isFaceDetected) android.R.color.holo_green_dark
                                else android.R.color.holo_red_dark)
                            )
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else imageProxy.close()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════════════════════

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val matrix = Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        matrix.postScale(-1f, 1f) // mirror front camera
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Compress [bitmap] to JPEG at 85% quality and return Base64 NO_WRAP. */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun resetToStep1() {
        livenessDetector.reset()
        capturedBitmap = null
        isFaceDetected = false
        showStep1Liveness()
        startCamera()
    }
}