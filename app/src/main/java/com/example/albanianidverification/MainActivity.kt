package com.example.albanianidverification

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.albanianidverification.databinding.ActivityMainBinding
import com.example.albanianidverification.utils.MRZParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
        
        binding.captureButton.setOnClickListener {
            takePhoto()
        }
        
        binding.instructionText.text = "Position the MRZ (bottom part) of the Albanian ID card within the frame"
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
            
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
            
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        
        binding.captureButton.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.statusText.text = "Capturing image..."
        
        val photoFile = File(
            externalMediaDirs.firstOrNull(),
            "${System.currentTimeMillis()}.jpg"
        )
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        baseContext,
                        "Photo capture failed: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.captureButton.isEnabled = true
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.statusText.text = ""
                }
                
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    binding.statusText.text = "Processing MRZ..."
                    processImage(photoFile)
                }
            }
        )
    }
    
    private fun processImage(photoFile: File) {
        try {
            val image = InputImage.fromFilePath(this, android.net.Uri.fromFile(photoFile))
            
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    val mrzData = MRZParser.extractMRZ(text)
                    
                    if (mrzData != null) {
                        binding.statusText.text = "MRZ detected! Ready for NFC reading."
                        Toast.makeText(this, "MRZ found: ${mrzData.documentNumber}", Toast.LENGTH_LONG).show()
                        
                        // Navigate to NFC reading activity
                        val intent = Intent(this, NFCReadActivity::class.java).apply {
                            putExtra("MRZ_DATA", mrzData)
                        }
                        startActivity(intent)
                        
                        binding.captureButton.isEnabled = true
                        binding.progressBar.visibility = android.view.View.GONE
                    } else {
                        binding.statusText.text = "No MRZ detected. Please try again."
                        Toast.makeText(this, "Could not detect MRZ. Please ensure the bottom part of the ID is visible.", Toast.LENGTH_LONG).show()
                        binding.captureButton.isEnabled = true
                        binding.progressBar.visibility = android.view.View.GONE
                    }
                    
                    // Clean up
                    photoFile.delete()
                }
                .addOnFailureListener { e ->
                    binding.statusText.text = "Text recognition failed"
                    Toast.makeText(this, "Failed to process image: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.captureButton.isEnabled = true
                    binding.progressBar.visibility = android.view.View.GONE
                    photoFile.delete()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            binding.captureButton.isEnabled = true
            binding.progressBar.visibility = android.view.View.GONE
        }
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        textRecognizer.close()
    }
}
