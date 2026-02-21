# Face Verification Engines — Setup Guide

## Overview

Your app now supports **4 face verification engines** that you can switch between in the UI during Step 2 (Capture):

| Engine | Accuracy (LFW) | Model Size | Speed | Offline | Setup Required |
|--------|----------------|-----------|-------|---------|----------------|
| **ML Kit (Legacy)** | ~70% real-world | 0 MB | Fast | ✅ | None |
| **FaceNet TFLite** | 99.63% | ~24 MB | ~500ms | ✅ | Download model |
| **MobileFaceNet TFLite** | 99.55% | ~5 MB | <100ms | ✅ | Download model |
| **DeepFace Server** | 99.65% | ~100 MB on PC | ~2s | ❌ WiFi | Run Python server |

---

## Step 1: Download TFLite Models

### Option A: FaceNet (facenet.tflite)

Download the FaceNet 128-d model from one of these sources:

1. **From the shubham0204 repo** (recommended):
   ```
   https://github.com/shubham0204/OnDevice-Face-Recognition-Android
   ```
   Clone the repo, the `facenet.tflite` file is in the assets folder.

2. **Direct from TensorFlow Hub / Kaggle**:
   Search for "FaceNet TFLite" on Kaggle — multiple community uploads exist.

3. **Convert from Keras yourself** (advanced):
   ```bash
   pip install tensorflow keras-facenet
   python -c "
   from keras_facenet import FaceNet
   embedder = FaceNet()
   import tensorflow as tf
   converter = tf.lite.TFLiteConverter.from_keras_model(embedder.model)
   tflite_model = converter.convert()
   with open('facenet.tflite', 'wb') as f:
       f.write(tflite_model)
   "
   ```

**Place the file in:**
```
app/src/main/assets/facenet.tflite
```

### Option B: MobileFaceNet (mobilefacenet.tflite)

Download the MobileFaceNet model:

1. **From the syaringan357 repo** (recommended):
   ```
   https://github.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing
   ```
   The `mobilefacenet.tflite` is in the `app/src/main/assets/` folder.

2. **From InsightFace model zoo**:
   ```
   https://github.com/deepinsight/insightface/tree/master/model_zoo
   ```
   Download the MobileFaceNet model and convert to TFLite.

**Place the file in:**
```
app/src/main/assets/mobilefacenet.tflite
```

### Verify Placement

Your `assets` folder should look like:
```
app/src/main/assets/
├── facenet.tflite          (~24 MB)
└── mobilefacenet.tflite    (~5 MB)
```

> **Note:** If a model file is missing, the app will still work — it just marks that
> engine as "(model missing)" in the spinner and won't let you select it.

---

## Step 2: Set Up DeepFace Server (Optional)

If you want to use the highest-accuracy option via your laptop:

### Install Dependencies

```bash
cd deepface_server
pip install -r requirements.txt
```

Or manually:
```bash
pip install deepface flask flask-cors pillow numpy
```

### Run the Server

```bash
python deepface_server.py
```

The first run will download the FaceNet512 model (~100 MB). After that it starts instantly.

Output:
```
============================================================
  DeepFace Verification Server
  Albanian ID Verification Project
============================================================

Loading DeepFace and Facenet512 model...
✓ Model pre-loaded and ready!

Server starting on http://0.0.0.0:5005
============================================================
```

### Find Your PC's IP Address

- **Windows**: Open cmd → `ipconfig` → look for "IPv4 Address" under your WiFi adapter
- **Mac**: Open Terminal → `ifconfig en0` → look for "inet"
- **Linux**: `ip addr show wlan0` or `hostname -I`

Example: `192.168.1.42`

### Configure in the Android App

1. In the app's Step 2, select **"DeepFace Server"** from the engine spinner
2. Enter the URL: `http://192.168.1.42:5005`
3. Make sure your phone and laptop are on the **same WiFi network**

### Test the Server

From your PC:
```bash
curl http://localhost:5005/health
```

Expected response:
```json
{"service": "DeepFace Verification Server", "status": "ok", "version": "1.0.0"}
```

Test with image files:
```bash
curl -X POST http://localhost:5005/verify-file \
     -F "img1=@id_photo.jpg" \
     -F "img2=@selfie.jpg"
```

---

## How It Works

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Android App (Kotlin)                       │
│                                                               │
│  ┌─────────────┐                                             │
│  │ Engine       │  User selects engine in UI                 │
│  │ Selector     │  ─────────────────────────┐                │
│  │ (Spinner)    │                           │                │
│  └─────────────┘                           ▼                │
│                              ┌──────────────────────┐        │
│                              │ FaceVerificationEngine│        │
│                              │ (Interface)           │        │
│                              └──────────┬───────────┘        │
│                     ┌───────────┬───────┼───────┬──────┐     │
│                     ▼           ▼       ▼       ▼      │     │
│              ┌──────────┐ ┌─────────┐ ┌──────┐ ┌────┐  │     │
│              │ ML Kit   │ │ FaceNet │ │Mobile│ │Deep│  │     │
│              │ Legacy   │ │ TFLite  │ │Face  │ │Face│  │     │
│              │          │ │ 128-d   │ │Net   │ │Srvr│  │     │
│              └──────────┘ └─────────┘ └──────┘ └──┬─┘  │     │
│                                                    │    │     │
└────────────────────────────────────────────────────┼────┘     
                                                     │ HTTP
                                              ┌──────▼──────┐  
                                              │ Python PC   │  
                                              │ Flask Server │  
                                              │ DeepFace     │  
                                              └─────────────┘  
```

### Engine Details

**ML Kit (Legacy)** — Your original approach. Compares facial landmarks, proportions,
contours, and pixel histograms. No deep learning embeddings, so accuracy is limited
for cross-domain (ID card → selfie) matching.

**FaceNet TFLite** — Google's FaceNet model converted to TFLite. Produces 128-dimensional
face embeddings. Uses per-image standardisation for preprocessing. Compared via cosine similarity.

**MobileFaceNet TFLite** — Purpose-built for mobile devices. Only ~5 MB and <100ms inference.
Produces 192-dimensional embeddings (may vary by model version — auto-detected at load time).

**DeepFace Server** — Sends images to a Python server running DeepFace with FaceNet512.
Highest accuracy but requires WiFi connectivity to a local server. Best for testing
and comparison during development.

### Threshold Tuning

For ID-to-selfie matching (which is harder than standard face verification), the
default thresholds are intentionally lenient:

| Engine | Match Threshold | Borderline Threshold |
|--------|----------------|---------------------|
| ML Kit Legacy | 60% | 50% |
| FaceNet TFLite | 40% cosine sim | 30% cosine sim |
| MobileFaceNet | 45% cosine sim | 35% cosine sim |
| DeepFace Server | Set by DeepFace | ~85% of threshold |

You can adjust these in the respective engine classes:
- `TFLiteVerificationEngine.kt` → `createFaceNet()` and `createMobileFaceNet()`
- `MLKitLegacyEngine.kt` → uses `FaceComparator.kt` thresholds
- `DeepFaceServerEngine.kt` → thresholds come from server response

### Tips for Better Results

1. **Good lighting** — Ensure the selfie is well-lit, matching ID card studio lighting
2. **Straight face** — Look directly at the camera, neutral expression
3. **Multiple attempts** — Try 2-3 captures; pick the best one
4. **Clean lens** — Wipe the front camera before capturing
5. **Lower thresholds** — If you consistently fail with your own ID, lower the threshold
   (e.g., change `matchThreshold = 0.40f` to `0.35f` in the TFLite config)

---

## File Structure (New Files)

```
app/src/main/
├── assets/
│   ├── facenet.tflite              ← YOU DOWNLOAD THIS
│   └── mobilefacenet.tflite        ← YOU DOWNLOAD THIS
├── java/.../verification/
│   ├── FaceVerificationEngine.kt   ← Interface + result types
│   ├── MLKitLegacyEngine.kt        ← Wraps existing FaceComparator
│   ├── TFLiteVerificationEngine.kt ← FaceNet + MobileFaceNet logic
│   ├── DeepFaceServerEngine.kt     ← HTTP client for Python server
│   └── VerificationEngineManager.kt← Manages all engines + selection
└── res/layout/
    └── activity_face_verification.xml  ← Updated with engine selector

deepface_server/
├── deepface_server.py              ← Python Flask server
└── requirements.txt                ← Python dependencies
```
