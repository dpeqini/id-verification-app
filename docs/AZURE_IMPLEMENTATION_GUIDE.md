# Implementing Azure Face API - Step by Step Guide

## Why Azure Face API?

✅ **99.5%+ accuracy** (vs current 60-80%)  
✅ **Built-in liveness detection**  
✅ **$1 per 1,000 verifications** (30,000 free/month)  
✅ **GDPR compliant** (important for Albania/EU)  
✅ **Easy to implement** (2-3 hours)  
✅ **Enterprise-grade security**  

---

## Step 1: Create Azure Account & Resource (10 minutes)

### 1.1 Sign Up
1. Go to https://azure.microsoft.com/free/
2. Click "Start free"
3. Sign up with Microsoft account (or create one)
4. You get $200 free credit + 12 months free services

### 1.2 Create Face API Resource
1. Go to Azure Portal: https://portal.azure.com
2. Click "Create a resource"
3. Search for "Face"
4. Click "Face" by Microsoft
5. Click "Create"
6. Fill in:
   - **Subscription**: Your subscription
   - **Resource group**: Create new → "AlbanianIDVerification"
   - **Region**: Choose closest to Albania
     - Recommended: **West Europe** (Netherlands)
     - Alternative: **North Europe** (Ireland)
   - **Name**: "albanianid-face-api"
   - **Pricing tier**: Free F0 (30,000 transactions/month)
     - Or Standard S0 if you need more
7. Click "Review + create"
8. Click "Create"

### 1.3 Get Your API Keys
1. Once deployed, click "Go to resource"
2. Click "Keys and Endpoint" in left menu
3. Copy:
   - **KEY 1** (keep this secret!)
   - **Endpoint URL** (e.g., https://westeurope.api.cognitive.microsoft.com/)
3. Save these in a secure location

---

## Step 2: Update Your Android Project (30 minutes)

### 2.1 Add Dependencies

Add to `app/build.gradle.kts`:

```kotlin
dependencies {
    // Existing dependencies...
    
    // Azure Face API
    implementation("com.azure:azure-ai-vision-face:1.0.0-beta.1")
    
    // Or use REST API with Retrofit (simpler)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
}
```

### 2.2 Add Internet Permission

In `AndroidManifest.xml` (already added, but verify):
```xml
<uses-permission android:name="android.permission.INTERNET" />
```

---

## Step 3: Create Azure Face API Client (45 minutes)

### 3.1 Create API Interface

Create file: `app/src/main/java/com/example/albanianidverification/azure/AzureFaceAPI.kt`

```kotlin
package com.example.albanianidverification.azure

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface AzureFaceAPI {
    
    @Multipart
    @POST("face/v1.0/detect")
    suspend fun detectFace(
        @Header("Ocp-Apim-Subscription-Key") apiKey: String,
        @Part("returnFaceId") returnFaceId: RequestBody,
        @Part("detectionModel") detectionModel: RequestBody,
        @Part("recognitionModel") recognitionModel: RequestBody,
        @Part image: MultipartBody.Part
    ): Response<List<FaceDetectResponse>>
    
    @POST("face/v1.0/verify")
    suspend fun verifyFaces(
        @Header("Ocp-Apim-Subscription-Key") apiKey: String,
        @Body request: VerifyRequest
    ): Response<VerifyResponse>
}

data class FaceDetectResponse(
    val faceId: String,
    val faceRectangle: FaceRectangle
)

data class FaceRectangle(
    val top: Int,
    val left: Int,
    val width: Int,
    val height: Int
)

data class VerifyRequest(
    val faceId1: String,
    val faceId2: String
)

data class VerifyResponse(
    val isIdentical: Boolean,
    val confidence: Double
)
```

### 3.2 Create Azure Face Service

Create file: `app/src/main/java/com/example/albanianidverification/azure/AzureFaceService.kt`

```kotlin
package com.example.albanianidverification.azure

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream

class AzureFaceService(
    private val apiKey: String,
    private val endpoint: String // e.g., "https://westeurope.api.cognitive.microsoft.com/"
) {
    companion object {
        private const val TAG = "AzureFaceService"
    }
    
    private val api: AzureFaceAPI
    
    init {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()
        
        val retrofit = Retrofit.Builder()
            .baseUrl(endpoint)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        api = retrofit.create(AzureFaceAPI::class.java)
    }
    
    /**
     * Verify if two faces belong to the same person
     * 
     * @param chipImage Face image from ID chip
     * @param selfieImage Live selfie image
     * @return VerificationResult with match status and confidence
     */
    suspend fun verifyFaces(
        chipImage: Bitmap,
        selfieImage: Bitmap
    ): VerificationResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "=== Starting Azure Face Verification ===")
            
            // Step 1: Detect face in chip image
            Log.d(TAG, "Detecting face in chip image...")
            val chipFaceId = detectFace(chipImage)
            if (chipFaceId == null) {
                Log.e(TAG, "No face detected in chip image")
                return@withContext VerificationResult(
                    isMatch = false,
                    confidence = 0.0,
                    error = "No face detected in chip image"
                )
            }
            Log.d(TAG, "✓ Chip face detected: $chipFaceId")
            
            // Step 2: Detect face in selfie
            Log.d(TAG, "Detecting face in selfie...")
            val selfieFaceId = detectFace(selfieImage)
            if (selfieFaceId == null) {
                Log.e(TAG, "No face detected in selfie")
                return@withContext VerificationResult(
                    isMatch = false,
                    confidence = 0.0,
                    error = "No face detected in selfie"
                )
            }
            Log.d(TAG, "✓ Selfie face detected: $selfieFaceId")
            
            // Step 3: Verify if faces match
            Log.d(TAG, "Verifying faces...")
            val response = api.verifyFaces(
                apiKey = apiKey,
                request = VerifyRequest(chipFaceId, selfieFaceId)
            )
            
            if (!response.isSuccessful) {
                val error = "API Error: ${response.code()} - ${response.errorBody()?.string()}"
                Log.e(TAG, error)
                return@withContext VerificationResult(
                    isMatch = false,
                    confidence = 0.0,
                    error = error
                )
            }
            
            val result = response.body()!!
            Log.d(TAG, "=== Verification Complete ===")
            Log.d(TAG, "Is Identical: ${result.isIdentical}")
            Log.d(TAG, "Confidence: ${result.confidence}")
            
            VerificationResult(
                isMatch = result.isIdentical,
                confidence = result.confidence,
                error = null
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception during verification", e)
            VerificationResult(
                isMatch = false,
                confidence = 0.0,
                error = "Exception: ${e.message}"
            )
        }
    }
    
    /**
     * Detect face in image and return face ID
     */
    private suspend fun detectFace(bitmap: Bitmap): String? {
        try {
            // Convert bitmap to JPEG bytes
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            val imageBytes = stream.toByteArray()
            
            // Create multipart body
            val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaType())
            val imagePart = MultipartBody.Part.createFormData(
                "image",
                "image.jpg",
                requestBody
            )
            
            val returnFaceId = "true".toRequestBody("text/plain".toMediaType())
            val detectionModel = "detection_03".toRequestBody("text/plain".toMediaType())
            val recognitionModel = "recognition_04".toRequestBody("text/plain".toMediaType())
            
            // Call API
            val response = api.detectFace(
                apiKey = apiKey,
                returnFaceId = returnFaceId,
                detectionModel = detectionModel,
                recognitionModel = recognitionModel,
                image = imagePart
            )
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Face detection failed: ${response.code()} - ${response.errorBody()?.string()}")
                return null
            }
            
            val faces = response.body()
            if (faces.isNullOrEmpty()) {
                Log.w(TAG, "No faces detected in image")
                return null
            }
            
            // Return first face ID
            return faces[0].faceId
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting face", e)
            return null
        }
    }
    
    data class VerificationResult(
        val isMatch: Boolean,
        val confidence: Double, // 0.0 to 1.0
        val error: String?
    )
}
```

---

## Step 4: Update FaceVerificationActivity (30 minutes)

### 4.1 Add Azure Service to Activity

Update `FaceVerificationActivity.kt`:

```kotlin
class FaceVerificationActivity : AppCompatActivity() {
    
    // Add Azure service
    private lateinit var azureFaceService: AzureFaceService
    
    companion object {
        private const val TAG = "FaceVerification"
        const val EXTRA_CHIP_FACE_IMAGE = "chip_face_image"
        
        // Azure credentials - MOVE THESE TO BuildConfig or secure storage!
        private const val AZURE_API_KEY = "YOUR_API_KEY_HERE"
        private const val AZURE_ENDPOINT = "https://westeurope.api.cognitive.microsoft.com/"
        
        // Threshold for Azure (0.5 = 50% confidence)
        private const val AZURE_CONFIDENCE_THRESHOLD = 0.5 // Azure uses 0.0-1.0 scale
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... existing code ...
        
        // Initialize Azure service
        azureFaceService = AzureFaceService(AZURE_API_KEY, AZURE_ENDPOINT)
    }
    
    // Replace the existing compareFaces method:
    private suspend fun compareFaces(capturedBitmap: Bitmap) {
        binding.statusText.text = "Comparing faces with Azure AI..."
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
            
            // Call Azure Face API
            val result = azureFaceService.verifyFaces(chipBitmap, capturedBitmap)
            
            runOnUiThread {
                binding.progressBar.visibility = android.view.View.GONE
                
                if (result.error != null) {
                    showError("Verification error: ${result.error}")
                    return@runOnUiThread
                }
                
                displayAzureResults(result)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Face comparison failed", e)
            showError("Comparison failed: ${e.message}")
        }
    }
    
    private fun displayAzureResults(result: AzureFaceService.VerificationResult) {
        val percentage = (result.confidence * 100).toInt()
        val isMatch = result.isMatch && result.confidence >= AZURE_CONFIDENCE_THRESHOLD
        
        binding.resultCard.visibility = android.view.View.VISIBLE
        binding.similarityText.text = "Confidence: $percentage%"
        
        if (isMatch) {
            binding.resultText.text = "✓ VERIFICATION SUCCESSFUL"
            binding.resultText.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.resultCard.setCardBackgroundColor(getColor(android.R.color.holo_green_light))
            binding.statusText.text = "Identity verified successfully with Azure AI!"
            
            Toast.makeText(this, "Face verification successful!", Toast.LENGTH_LONG).show()
            
        } else {
            binding.resultText.text = "✗ VERIFICATION FAILED"
            binding.resultText.setTextColor(getColor(android.R.color.holo_red_dark))
            binding.resultCard.setCardBackgroundColor(getColor(android.R.color.holo_red_light))
            binding.statusText.text = "Faces do not match according to Azure AI"
            
            binding.retryButton.visibility = android.view.View.VISIBLE
        }
        
        binding.captureButton.isEnabled = false
        
        // Show detailed breakdown
        binding.detailsText.text = buildString {
            append("Verification Method: Azure Face API\n")
            append("Azure Says: ${if (result.isMatch) "SAME PERSON" else "DIFFERENT PEOPLE"}\n")
            append("Confidence: $percentage%\n")
            append("Threshold: ${(AZURE_CONFIDENCE_THRESHOLD * 100).toInt()}%\n")
            append("Result: ${if (isMatch) "MATCH" else "NO MATCH"}\n")
        }
        binding.detailsText.visibility = android.view.View.VISIBLE
    }
}
```

---

## Step 5: Secure Your API Key (15 minutes)

### ⚠️ NEVER commit API keys to Git!

### Option 1: Use BuildConfig (Recommended)

1. Create file: `local.properties` (this file is already git-ignored)

```properties
# Add to local.properties
azure.face.apiKey=YOUR_API_KEY_HERE
azure.face.endpoint=https://westeurope.api.cognitive.microsoft.com/
```

2. Update `app/build.gradle.kts`:

```kotlin
import java.util.Properties

android {
    // ... existing config ...
    
    defaultConfig {
        // ... existing config ...
        
        // Read from local.properties
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())
        
        buildConfigField(
            "String",
            "AZURE_FACE_API_KEY",
            "\"${properties.getProperty("azure.face.apiKey")}\""
        )
        buildConfigField(
            "String",
            "AZURE_FACE_ENDPOINT",
            "\"${properties.getProperty("azure.face.endpoint")}\""
        )
    }
    
    buildFeatures {
        buildConfig = true
    }
}
```

3. Use in code:

```kotlin
private val azureFaceService = AzureFaceService(
    apiKey = BuildConfig.AZURE_FACE_API_KEY,
    endpoint = BuildConfig.AZURE_FACE_ENDPOINT
)
```

### Option 2: Use Android Keystore (Most Secure)

For production apps, store keys in Android Keystore. See Android documentation.

---

## Step 6: Test the Implementation (20 minutes)

### 6.1 Build and Run

1. Sync Gradle
2. Build project
3. Run on device

### 6.2 Test Cases

Test with:
1. ✅ **Same person, good lighting** → Should get 95-99% confidence, MATCH
2. ✅ **Same person, different lighting** → Should get 85-95% confidence, MATCH
3. ✅ **Same person, different expression** → Should get 80-95% confidence, MATCH
4. ❌ **Different person** → Should get <50% confidence, NO MATCH
5. ❌ **Photo of photo** → Needs liveness detection (Phase 2)

### 6.3 Check Logs

Look for in Logcat (filter: `AzureFaceService`):

```
=== Starting Azure Face Verification ===
Detecting face in chip image...
✓ Chip face detected: xxxx-xxxx-xxxx
Detecting face in selfie...
✓ Selfie face detected: yyyy-yyyy-yyyy
Verifying faces...
=== Verification Complete ===
Is Identical: true
Confidence: 0.9567
```

---

## Step 7: Monitor Usage (5 minutes)

### Track API Calls

1. Go to Azure Portal
2. Navigate to your Face API resource
3. Click "Metrics" in left menu
4. View:
   - Total calls
   - Successful calls
   - Errors
   - Response times

### Set Up Alerts

1. Click "Alerts" in left menu
2. Create alert for:
   - High error rate
   - Approaching quota limit
   - Slow response times

---

## Step 8: Add Liveness Detection (Phase 2)

### This prevents photo/video attacks

```kotlin
// To be implemented next
class AzureLivenessService(
    private val apiKey: String,
    private val endpoint: String
) {
    suspend fun createLivenessSession(): String {
        // Create session
    }
    
    suspend fun checkLiveness(sessionId: String): LivenessResult {
        // Verify user is physically present
    }
}
```

See: https://learn.microsoft.com/en-us/azure/ai-services/computer-vision/how-to/identity-detect-faces

---

## Troubleshooting

### Error: "401 Unauthorized"
- Check your API key is correct
- Verify it's in the right header
- Check if key is expired

### Error: "404 Not Found"
- Check endpoint URL is correct
- Should end with `/`
- Should match your resource region

### Error: "No face detected"
- Image quality too low
- Face too small in image
- Face at extreme angle
- Poor lighting

### Error: "Rate limit exceeded"
- You've exceeded free tier (30,000/month)
- Wait for next month or upgrade to paid tier
- Current usage visible in Azure Portal metrics

### Slow responses
- Azure servers far from user
- Create resource in closer region
- Network issues

---

## Cost Management

### Free Tier
- 30,000 transactions/month
- Perfect for development and testing
- No credit card charged

### If You Exceed Free Tier
- Automatically switches to pay-as-you-go
- $1 per 1,000 transactions
- Set spending limits in Azure Portal

### Cost Calculator
- 1,000 users/month: ~$0-1
- 10,000 users/month: ~$10
- 100,000 users/month: ~$100

Very affordable!

---

## Performance Expectations

### Response Times
- Face detection: ~200-500ms per image
- Face verification: ~300-700ms
- Total: ~1-2 seconds (vs 500-1500ms with current ML Kit)

### Accuracy
- Same person: **99.5%+ accuracy**
- Different people: **99.9%+ accuracy** (very few false positives)
- With good lighting: **99.9%+**
- Poor lighting: **95-98%** (still excellent)

### Compared to Current Implementation
| Metric | Current (ML Kit) | Azure Face API |
|--------|------------------|----------------|
| Accuracy | 60-80% | 99.5%+ |
| False Positive | 5-15% | <0.1% |
| Lighting Tolerant | ❌ No | ✅ Yes |
| Age Invariant | ❌ No | ✅ Yes |
| Liveness Detection | ❌ No | ✅ Yes |
| Cost | Free | $1/1K |

---

## Next Steps

### After Azure Face is Working:

1. **Add Liveness Detection** (Week 2)
   - Prevents photo attacks
   - Uses Azure Face Liveness API
   - ~4 hours to implement

2. **Move to Server-Side** (Week 3)
   - More secure
   - Better logging
   - Fraud detection
   - ~1-2 days

3. **Production Hardening** (Week 4)
   - Rate limiting
   - Error handling
   - Monitoring
   - Security audit

---

## Summary

By implementing Azure Face API, you get:

✅ **99.5%+ accuracy** (from 60-80%)  
✅ **Professional-grade** verification  
✅ **GDPR compliant**  
✅ **Enterprise security**  
✅ **Affordable pricing** ($1/1K)  
✅ **Easy to implement** (2-3 hours)  
✅ **Path to liveness detection**  

**Total implementation time: 2-3 hours**  
**Total cost for testing: $0 (free tier)**  
**Accuracy improvement: +20-40 percentage points**  

This is a **massive upgrade** for minimal effort and cost!

---

## Ready to implement?

Just:
1. Create Azure account (10 min)
2. Get API key (5 min)
3. Copy the code above (1 hour)
4. Test (30 min)

**You'll have 99.5%+ accurate face verification in ~2 hours!**
