# Face Verification Feature

## Overview
The Face Verification feature allows users to verify their identity by comparing a live selfie with the face photo stored in their Albanian ID card's NFC chip.

## How It Works

### 1. Flow
```
NFC Read → Extract Face Image → Launch Verification → Capture Live Photo → Compare → Result
```

### 2. Technical Implementation

#### Face Detection
- Uses **Google ML Kit Face Detection**
- Real-time face detection during camera preview
- Detects face presence and enables capture button only when a face is visible
- Visual indicator shows when face is detected (green) or not (red)

#### Face Comparison Algorithm
The app uses a multi-factor similarity score combining:

1. **Face Size Similarity (20% weight)**
   - Compares the relative size of the face in both images
   - Ensures similar framing and distance

2. **Facial Features (20% weight)**
   - Smile probability comparison
   - Eye open probability comparison
   - Helps ensure similar facial expressions

3. **Head Pose (20% weight)**
   - Compares yaw (left/right rotation)
   - Compares pitch (up/down tilt)
   - Compares roll (side tilt)
   - Ensures similar head orientation

4. **Pixel-Level Histogram Comparison (40% weight)**
   - Most important factor
   - Compares color distribution in face regions
   - Crops and normalizes both faces
   - Calculates histogram correlation

#### Similarity Threshold
- **Default threshold: 75%**
- Configurable in `FaceVerificationActivity.SIMILARITY_THRESHOLD`
- Verification succeeds if similarity ≥ threshold

### 3. User Interface

#### Components:
1. **Reference Photo Card**
   - Shows the face image extracted from the ID chip
   - Labeled as "Reference Photo (from ID chip)"

2. **Live Camera Preview**
   - Front-facing camera
   - Real-time face detection indicator
   - Face guide overlay to help positioning
   - Green/red indicator shows face detection status

3. **Capture Button**
   - Disabled until face is detected
   - Enabled when face is visible in frame
   - Captures photo and triggers comparison

4. **Results Card**
   - Shows similarity percentage
   - Displays ✓ SUCCESS or ✗ FAILED
   - Color-coded (green for success, red for failure)
   - Shows match threshold and details

5. **Retry Button**
   - Appears if verification fails
   - Allows user to try again

### 4. Verification Process

```kotlin
Step 1: User holds ID card to NFC reader
  ↓
Step 2: Face image extracted from chip
  ↓
Step 3: User clicks "Verify Identity"
  ↓
Step 4: FaceVerificationActivity launches
  ↓
Step 5: Camera opens, face detection starts
  ↓
Step 6: When face detected, capture button enables
  ↓
Step 7: User clicks "Capture Face"
  ↓
Step 8: Photo captured and analysis begins
  ↓
Step 9: ML Kit detects faces in both images
  ↓
Step 10: Similarity calculated using multiple factors
  ↓
Step 11: Result displayed (MATCH or NO MATCH)
```

## Key Features

### Real-Time Face Detection
- Green indicator when face is detected
- Red indicator when no face is visible
- Capture button only enabled when face is detected
- Helps ensure good quality captures

### Multi-Factor Comparison
- Not just a single metric
- Combines geometric features, expressions, and pixel data
- More robust than simple template matching

### User Feedback
- Clear visual feedback at every step
- Progress indicator during processing
- Detailed results with percentage
- Helpful error messages

### Smart Camera Handling
- Uses front camera (for selfies)
- Auto-rotation handling
- Mirror correction for front camera
- Proper bitmap conversion

## Customization Options

### Adjusting Similarity Threshold
Edit in `FaceVerificationActivity.kt`:
```kotlin
companion object {
    private const val SIMILARITY_THRESHOLD = 0.75f // Change this value
}
```

**Recommendations:**
- **0.70 (70%)**: More lenient, fewer false rejections, more false accepts
- **0.75 (75%)**: Balanced (default)
- **0.80 (80%)**: More strict, fewer false accepts, more false rejections

### Changing Weight Factors
In the `calculateFaceSimilarity()` method, adjust weights:
```kotlin
matchScore += sizeSimilarity * 0.2f // Face size weight
matchScore += smileSimilarity * 0.1f // Smile weight
matchScore += eyeSimilarity * 0.1f // Eye weight
matchScore += poseSimilarity * 0.2f // Pose weight
matchScore += pixelSimilarity * 0.4f // Pixel similarity weight
```

**Total must equal 1.0 (100%)**

### Improving Accuracy

#### For Better Results:
1. **Good Lighting**
   - Ensure well-lit environment
   - Avoid harsh shadows
   - Natural light works best

2. **Similar Expression**
   - Try to match the expression in the chip photo
   - Usually neutral/serious expression

3. **Similar Head Position**
   - Face the camera straight on
   - Same angle as chip photo
   - Keep head level

4. **Remove Accessories**
   - Take off glasses if not in chip photo
   - Remove hat/cap
   - Move hair away from face

## Known Limitations

### 1. Algorithm Limitations
- Not a professional face recognition system
- Cannot compete with commercial solutions (Face ID, etc.)
- May struggle with:
  - Significant aging
  - Major changes in appearance
  - Very different lighting conditions
  - Different facial expressions

### 2. ML Kit Limitations
- ML Kit provides landmarks and features, not embeddings
- No deep learning face recognition
- Simple feature comparison only

### 3. Quality Dependencies
- Chip photo quality varies by ID card
- Old IDs may have low-resolution photos
- Compressed JPEG2000 images may lose detail

## Improving the System

### Professional Face Recognition
For production use, consider:

1. **Azure Face API**
   ```kotlin
   // Using Microsoft Azure Face API
   implementation("com.microsoft.azure:azure-cognitive-services-face:1.0.0")
   ```
   - Professional face matching
   - Face embeddings
   - Liveness detection

2. **AWS Rekognition**
   ```kotlin
   implementation("com.amazonaws:aws-android-sdk-rekognition:2.x.x")
   ```
   - High-accuracy face matching
   - Face comparison API
   - Celebrity recognition

3. **Face++ / Megvii**
   - Commercial face recognition API
   - Very high accuracy
   - Used in production systems

### Adding Liveness Detection
Prevent photo spoofing:

```kotlin
// Example with ML Kit Selfie Segmentation
implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta5")

// Check for:
// 1. Blink detection
// 2. Head movement
// 3. Smile detection
// 4. Challenge-response (look left, right, smile, etc.)
```

### Server-Side Verification
Move face comparison to backend:

```kotlin
// Send both images to server
val request = FaceComparisonRequest(
    referenceImage = chipFaceBase64,
    capturedImage = selfieBase64
)

// Server uses professional library
// Python: face_recognition, DeepFace
// Returns similarity score
```

## Testing Tips

### Test Cases:
1. **Perfect Match**: Same person, same lighting
2. **Good Match**: Same person, different lighting
3. **Partial Match**: Same person, glasses on/off
4. **No Match**: Different people
5. **Edge Cases**: 
   - Side profile
   - Poor lighting
   - Obscured face

### Debug Information
Enable detailed logging:
```kotlin
// In FaceVerificationActivity
Log.d(TAG, "Similarity breakdown:")
Log.d(TAG, "- Size: $sizeSimilarity")
Log.d(TAG, "- Pose: $poseSimilarity")
Log.d(TAG, "- Pixels: $pixelSimilarity")
Log.d(TAG, "- Total: $similarity")
```

## Security Considerations

### Current Implementation
- Basic face comparison only
- No liveness detection
- Vulnerable to photo attacks
- Should not be used for high-security authentication

### Recommendations for Production:
1. **Add Liveness Detection**
   - Require blinks
   - Require head movement
   - Challenge-response

2. **Server-Side Verification**
   - Don't trust client-side results
   - Use professional APIs
   - Log all verification attempts

3. **Multi-Factor Authentication**
   - Face + PIN
   - Face + Fingerprint
   - Face + OTP

4. **Audit Trail**
   - Log all verification attempts
   - Store timestamps
   - Record success/failure
   - Monitor for suspicious patterns

## Troubleshooting

### "No face detected"
- Ensure good lighting
- Face the camera directly
- Move closer or further away
- Remove obstructions (mask, hand, etc.)

### "Verification Failed" with high similarity
- Threshold might be too high
- Adjust SIMILARITY_THRESHOLD
- Check lighting conditions
- Try neutral expression

### "Verification Failed" with low similarity
- Different person (correct behavior)
- Very different appearance
- Bad lighting in one photo
- Significant aging

### Camera not starting
- Check camera permissions
- Ensure camera is available
- Try restarting app
- Check logcat for errors

## Performance Notes

- **Face detection**: ~30-60ms per frame (real-time)
- **Face comparison**: ~500-1500ms (depends on image size)
- **Memory usage**: ~50-100MB during operation
- **Battery impact**: Moderate (camera + ML processing)

## Future Enhancements

### Planned Improvements:
1. ✅ Basic face comparison (implemented)
2. ⬜ Liveness detection
3. ⬜ Server-side verification
4. ⬜ Face quality scoring
5. ⬜ Anti-spoofing measures
6. ⬜ Professional face recognition integration
7. ⬜ Verification history
8. ⬜ Multi-angle capture
9. ⬜ Video-based verification
10. ⬜ Age progression compensation

## References

- [ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection)
- [Face Recognition Best Practices](https://nvlpubs.nist.gov/nistpubs/ir/2021/NIST.IR.8429.pdf)
- [ISO/IEC 19794-5](https://www.iso.org/standard/50867.html) - Face Image Data Standard
- [Biometric Verification Standards](https://www.iso.org/standard/69320.html)
