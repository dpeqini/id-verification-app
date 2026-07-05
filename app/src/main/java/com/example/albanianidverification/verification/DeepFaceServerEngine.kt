package com.example.albanianidverification.verification

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Face verification engine that calls a DeepFace Python server over HTTP.
 *
 * The server exposes POST /verify which accepts two base64-encoded images
 * and returns { verified, distance, threshold, model, time }.
 *
 * Default URL: http://<serverIp>:5005/verify
 *
 * To start the server:
 *   pip install deepface flask
 *   python deepface_server.py
 */
class DeepFaceServerEngine(
    private var serverUrl: String = DEFAULT_SERVER_URL
) : FaceVerificationEngine {

    override val displayName = "DeepFace Server"
    override val description = "Python server • FaceNet512 • highest accuracy • needs WiFi"

    companion object {
        private const val TAG = "DeepFaceEngine"
        const val DEFAULT_SERVER_URL = "http://192.168.1.132:5005"
        private const val TIMEOUT_MS = 30_000
    }

    fun updateServerUrl(url: String) {
        serverUrl = url.trimEnd('/')
    }

    fun getServerUrl(): String = serverUrl

    override suspend fun verify(
        referenceFace: Bitmap,
        capturedFace: Bitmap
    ): VerificationResult = withContext(Dispatchers.IO) {

        val start = System.currentTimeMillis()

        try {
            val refBase64 = bitmapToBase64(referenceFace)
            val capBase64 = bitmapToBase64(capturedFace)

            val jsonBody = JSONObject().apply {
                put("img1", refBase64)
                put("img2", capBase64)
                put("model_name", "Facenet512")
                put("distance_metric", "cosine")
            }

            val url = URL("$serverUrl/verify")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }

            // Send request
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            // Read response
            val responseCode = conn.responseCode
            val responseBody = if (responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                conn.disconnect()
                throw RuntimeException("Server returned $responseCode: $error")
            }
            conn.disconnect()

            val result = JSONObject(responseBody)
            val elapsed = System.currentTimeMillis() - start

            Log.d(TAG, "DeepFace response: $result  (${elapsed}ms)")

            val verified = result.optBoolean("verified", false)
            val distance = result.optDouble("distance", 1.0).toFloat()
            val threshold = result.optDouble("threshold", 0.40).toFloat()
            val model = result.optString("model", "Facenet512")

            // DeepFace 'distance' is cosine distance (0 = identical).
            // Convert to similarity: sim = 1 - distance
            val similarity = (1f - distance).coerceIn(0f, 1f)

            VerificationResult(
                similarity = similarity,
                isMatch = verified,
                isBorderline = !verified && similarity >= (1f - threshold) * 0.85f,
                threshold = 1f - threshold, // Convert distance threshold to similarity
                borderlineThreshold = (1f - threshold) * 0.85f,
                engineName = "DeepFace ($model)",
                details = "Dist: %.4f  •  Thr: %.4f  •  ${elapsed}ms  •  Model: $model".format(
                    distance, threshold
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "DeepFace server error", e)
            val elapsed = System.currentTimeMillis() - start

            VerificationResult(
                similarity = 0f,
                isMatch = false,
                isBorderline = false,
                threshold = 0.60f,
                borderlineThreshold = 0.50f,
                engineName = "DeepFace (Error)",
                details = "Server error (${elapsed}ms): ${e.message}\n" +
                        "Make sure the Python server is running and reachable at $serverUrl"
            )
        }
    }

    override fun release() { /* no persistent resources */ }

    // ---- helpers -----------------------------------------------------------

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}