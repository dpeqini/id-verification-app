# Quick Start: Face Verification

## What is Face Verification?

After reading an Albanian ID card's NFC chip, the app can verify your identity by comparing a live selfie with the photo stored in the chip.

## How to Use

### Step 1: Complete NFC Reading
1. First, scan the MRZ (bottom of ID card)
2. Then read the NFC chip
3. Wait for face image to be extracted

### Step 2: Start Face Verification
1. Look for the **"Verify Identity"** button on the NFC results screen
2. This button only appears if a face image was successfully extracted from the chip
3. Tap the button to start verification

### Step 3: Position Your Face
1. The front camera will open
2. Position your face in the frame
3. Look at the **face detection indicator** (top right):
   - 🟢 **Green** = Face detected, ready to capture
   - 🔴 **Red** = No face detected, adjust position

### Step 4: Capture Your Photo
1. When the indicator is green, the **"Capture Face"** button will enable
2. Try to match your expression to the chip photo (usually neutral)
3. Tap **"Capture Face"**
4. Wait while the app analyzes both photos (~1-2 seconds)

### Step 5: View Results
- **✓ VERIFICATION SUCCESSFUL** (green) = Faces match! ≥75% similarity
- **✗ VERIFICATION FAILED** (red) = Faces don't match enough

#### If Verification Fails:
1. Tap **"Try Again"**
2. Adjust lighting (brighter is usually better)
3. Try to match the expression in the chip photo
4. Remove glasses if they weren't in the chip photo
5. Keep your face straight and level

## Tips for Best Results

### ✅ DO:
- Use good lighting (natural light works best)
- Face the camera directly
- Keep a neutral expression (like the chip photo)
- Remove glasses if not in chip photo
- Keep your face centered in the frame
- Wait for the green indicator before capturing

### ❌ DON'T:
- Don't use harsh overhead lighting
- Don't tilt your head
- Don't smile if the chip photo is serious
- Don't move while capturing
- Don't cover parts of your face
- Don't capture in very dark conditions

## Understanding the Results

### Similarity Score
- Shows how closely the two faces match
- Expressed as a percentage (0-100%)
- **75% or higher** = Verification successful
- **Below 75%** = Verification failed

### What Gets Compared?
1. **Face size and position** (20%)
2. **Head angle and pose** (20%)
3. **Facial features** (20%)
   - Smile
   - Eyes open/closed
4. **Pixel-level appearance** (40%)
   - Color patterns
   - Facial structure

## Troubleshooting

### "No face detected"
**Problem**: Red indicator, capture button disabled  
**Solutions**:
- Move closer or further from camera
- Improve lighting
- Ensure your full face is visible
- Remove obstructions (hands, mask, etc.)

### Low Similarity Score
**Problem**: Verification fails even though it's you  
**Possible causes**:
- Very different lighting conditions
- Different facial expression
- Wearing/not wearing glasses
- Significant change in appearance since ID photo

**Solutions**:
- Try again with better lighting
- Match the expression in your chip photo
- Remove/add glasses to match chip photo
- Ensure neutral head position

### Camera Won't Start
**Problem**: Black screen or error message  
**Solutions**:
- Grant camera permission in Settings
- Restart the app
- Check if another app is using the camera
- Restart your phone

### Image Quality Issues
**Problem**: Blurry or poor quality capture  
**Solutions**:
- Clean your camera lens
- Ensure good lighting
- Hold phone steady
- Don't move while capturing

## Privacy & Security

### What Gets Stored?
- **Nothing!** The app doesn't store or transmit any images
- All processing happens locally on your device
- Images are only kept in memory during verification
- No server communication for face verification

### Is It Secure?
**For demonstration**: Yes, basic security
**For production**: Additional measures needed:
- This is NOT a high-security biometric system
- Does not include liveness detection
- Could be fooled by photos (in theory)
- Suitable for low-risk verification only

## Technical Details

### Algorithm
- Uses Google ML Kit Face Detection
- Multi-factor similarity scoring
- Combines geometric and pixel-level features
- 75% threshold for positive match

### Accuracy
- **Good matches**: Usually 80-95% similarity
- **Different people**: Usually <50% similarity
- **Edge cases**: 60-80% (may pass or fail)

### Processing Time
- Real-time face detection: 30-60ms per frame
- Face comparison: 500-1500ms
- Total time: ~2-3 seconds from capture to results

## Advanced Options

### For Developers

#### Adjust Threshold
Edit `FaceVerificationActivity.kt`:
```kotlin
private const val SIMILARITY_THRESHOLD = 0.75f // Change this
```

- **Lower** (0.65-0.70): More lenient, fewer rejections
- **Higher** (0.80-0.85): More strict, fewer false accepts

#### Enable Debug Logging
Check Android Studio Logcat with filter: `FaceVerification`

Shows detailed breakdown:
- Size similarity
- Pose similarity  
- Feature similarity
- Pixel similarity
- Final score

## FAQs

**Q: Why did verification fail even though it's clearly me?**  
A: Many factors affect similarity: lighting, expression, head angle, time since ID photo was taken, glasses, etc. Try again with adjustments.

**Q: Can someone use my photo to pass verification?**  
A: Currently, yes (no liveness detection). This is why it's not suitable for high-security use cases. Future versions will add liveness detection.

**Q: How accurate is this compared to Face ID or other systems?**  
A: This is a basic implementation. Professional systems (Face ID, banking apps) use deep learning and specialized hardware. This is suitable for low-stakes verification only.

**Q: Will this work with old ID cards?**  
A: Only if the ID has an NFC chip with a face image. Cards issued before ~2009 typically don't have chips.

**Q: Can I verify someone else's ID?**  
A: Technically yes, but the face in the selfie needs to match the face on the ID. It's designed for self-verification.

## Next Steps

After successful face verification:
- Identity is confirmed
- The verification session ends
- You can start a new verification or exit

For production use:
- See [FACE_VERIFICATION.md](FACE_VERIFICATION.md) for implementation details
- Consider adding liveness detection
- Implement server-side verification
- Add logging and audit trails
- Review security requirements

## Need Help?

1. Check [TROUBLESHOOTING.md](TROUBLESHOOTING.md)
2. Check [FACE_VERIFICATION.md](FACE_VERIFICATION.md)
3. Enable debug logging in Android Studio
4. Review error messages in the app
5. Check camera and NFC permissions

## Summary

```
1. Read NFC chip → Extract face image
2. Tap "Verify Identity"
3. Position face (wait for green indicator)
4. Tap "Capture Face"
5. View similarity score and result
6. Retry if needed
```

**Remember**: Good lighting and proper positioning are key to successful verification!
