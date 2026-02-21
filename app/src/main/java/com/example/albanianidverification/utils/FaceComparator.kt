package com.example.albanianidverification.utils

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Utility class for comparing faces with improved algorithm
 *
 * Features:
 * - Landmark distance comparison (30%)
 * - Face proportions (20%)
 * - Weighted contours (15%)
 * - Pixel histogram (25%)
 * - Eye region comparison (10%)
 */
class FaceComparator {

    companion object {
        private const val TAG = "FaceComparator"
        private const val SIMILARITY_THRESHOLD = 0.60f  // Lowered from 0.70f to 0.60f
        private const val BORDERLINE_THRESHOLD = 0.50f  // Below this is clear reject

        /**
         * Compare two face images and return similarity score (0.0 to 1.0)
         */
        suspend fun compareFaces(bitmap1: Bitmap, bitmap2: Bitmap): Float {
            return withContext(Dispatchers.Default) {
                try {
                    val options = FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                        .build()

                    val detector = FaceDetection.getClient(options)

                    val image1 = InputImage.fromBitmap(bitmap1, 0)
                    val image2 = InputImage.fromBitmap(bitmap2, 0)

                    val faces1 = detector.process(image1).await()
                    val faces2 = detector.process(image2).await()

                    Log.d(TAG, "Faces detected - Image1: ${faces1.size}, Image2: ${faces2.size}")

                    if (faces1.isEmpty() || faces2.isEmpty()) {
                        Log.w(TAG, "No face detected in one or both images")
                        detector.close()
                        return@withContext 0f
                    }

                    val face1 = faces1[0]
                    val face2 = faces2[0]

                    var matchScore = 0f
                    var totalWeight = 0f

                    // 1. Landmark distances - 30%
                    val landmarkSimilarity = compareLandmarkDistances(face1, face2, bitmap1, bitmap2)
                    matchScore += landmarkSimilarity * 0.30f
                    totalWeight += 0.30f
                    Log.d(TAG, "Landmark similarity: $landmarkSimilarity")

                    // 2. Face proportions - 20%
                    val proportionSimilarity = compareFaceProportions(face1, face2, bitmap1, bitmap2)
                    matchScore += proportionSimilarity * 0.20f
                    totalWeight += 0.20f
                    Log.d(TAG, "Proportion similarity: $proportionSimilarity")

                    // 3. Weighted contours - 15%
                    val contours1 = face1.allContours
                    val contours2 = face2.allContours
                    if (contours1.isNotEmpty() && contours2.isNotEmpty()) {
                        val contourSimilarity = compareContoursWeighted(contours1, contours2)
                        matchScore += contourSimilarity * 0.15f
                        totalWeight += 0.15f
                        Log.d(TAG, "Contour similarity: $contourSimilarity")
                    }

                    // 4. Pixel histogram - 25%
                    val pixelSimilarity = compareHistograms(
                        cropFace(bitmap1, face1.boundingBox),
                        cropFace(bitmap2, face2.boundingBox)
                    )
                    matchScore += pixelSimilarity * 0.25f
                    totalWeight += 0.25f
                    Log.d(TAG, "Pixel similarity: $pixelSimilarity")

                    // 5. Eye region comparison - 10%
                    val eyeSimilarity = compareEyeRegions(face1, face2, bitmap1, bitmap2)
                    matchScore += eyeSimilarity * 0.10f
                    totalWeight += 0.10f
                    Log.d(TAG, "Eye similarity: $eyeSimilarity")

                    detector.close()

                    val finalSimilarity = if (totalWeight > 0) {
                        (matchScore / totalWeight).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                    Log.d(TAG, "Final similarity: $finalSimilarity")
                    finalSimilarity

                } catch (e: Exception) {
                    Log.e(TAG, "Error comparing faces", e)
                    0f
                }
            }
        }

        fun isMatch(similarity: Float): Boolean {
            return similarity >= SIMILARITY_THRESHOLD
        }

        fun isBorderline(similarity: Float): Boolean {
            return similarity >= BORDERLINE_THRESHOLD && similarity < SIMILARITY_THRESHOLD
        }

        fun isClearReject(similarity: Float): Boolean {
            return similarity < BORDERLINE_THRESHOLD
        }

        fun getThreshold(): Float {
            return SIMILARITY_THRESHOLD
        }

        fun getBorderlineThreshold(): Float {
            return BORDERLINE_THRESHOLD
        }

        // ===== PRIVATE HELPER METHODS =====

        private fun compareLandmarkDistances(
            face1: Face,
            face2: Face,
            bitmap1: Bitmap,
            bitmap2: Bitmap
        ): Float {
            val leftEye1 = face1.getLandmark(FaceLandmark.LEFT_EYE)
            val rightEye1 = face1.getLandmark(FaceLandmark.RIGHT_EYE)
            val nose1 = face1.getLandmark(FaceLandmark.NOSE_BASE)
            val mouth1 = face1.getLandmark(FaceLandmark.MOUTH_BOTTOM)

            val leftEye2 = face2.getLandmark(FaceLandmark.LEFT_EYE)
            val rightEye2 = face2.getLandmark(FaceLandmark.RIGHT_EYE)
            val nose2 = face2.getLandmark(FaceLandmark.NOSE_BASE)
            val mouth2 = face2.getLandmark(FaceLandmark.MOUTH_BOTTOM)

            if (leftEye1 == null || rightEye1 == null || nose1 == null || mouth1 == null ||
                leftEye2 == null || rightEye2 == null || nose2 == null || mouth2 == null) {
                return 0.5f
            }

            val faceWidth1 = face1.boundingBox.width().toFloat()
            val faceWidth2 = face2.boundingBox.width().toFloat()

            // Calculate normalized distances
            val eyeDist1 = distance(leftEye1.position, rightEye1.position) / faceWidth1
            val eyeDist2 = distance(leftEye2.position, rightEye2.position) / faceWidth2
            val eyeDistSim = 1f - kotlin.math.abs(eyeDist1 - eyeDist2)

            val leftEyeNose1 = distance(leftEye1.position, nose1.position) / faceWidth1
            val leftEyeNose2 = distance(leftEye2.position, nose2.position) / faceWidth2
            val leftEyeNoseSim = 1f - kotlin.math.abs(leftEyeNose1 - leftEyeNose2)

            val rightEyeNose1 = distance(rightEye1.position, nose1.position) / faceWidth1
            val rightEyeNose2 = distance(rightEye2.position, nose2.position) / faceWidth2
            val rightEyeNoseSim = 1f - kotlin.math.abs(rightEyeNose1 - rightEyeNose2)

            val noseMouth1 = distance(nose1.position, mouth1.position) / faceWidth1
            val noseMouth2 = distance(nose2.position, mouth2.position) / faceWidth2
            val noseMouthSim = 1f - kotlin.math.abs(noseMouth1 - noseMouth2)

            val leftEyeMouth1 = distance(leftEye1.position, mouth1.position) / faceWidth1
            val leftEyeMouth2 = distance(leftEye2.position, mouth2.position) / faceWidth2
            val leftEyeMouthSim = 1f - kotlin.math.abs(leftEyeMouth1 - leftEyeMouth2)

            val avgSimilarity = (eyeDistSim + leftEyeNoseSim + rightEyeNoseSim +
                    noseMouthSim + leftEyeMouthSim) / 5f

            return avgSimilarity.coerceIn(0f, 1f)
        }

        private fun compareFaceProportions(
            face1: Face,
            face2: Face,
            bitmap1: Bitmap,
            bitmap2: Bitmap
        ): Float {
            val ratio1 = face1.boundingBox.height().toFloat() / face1.boundingBox.width()
            val ratio2 = face2.boundingBox.height().toFloat() / face2.boundingBox.width()
            val ratioSim = 1f - kotlin.math.min(kotlin.math.abs(ratio1 - ratio2), 0.3f) / 0.3f

            val sizeRatio1 = face1.boundingBox.width().toFloat() / bitmap1.width
            val sizeRatio2 = face2.boundingBox.width().toFloat() / bitmap2.width
            val sizeSim = 1f - kotlin.math.min(kotlin.math.abs(sizeRatio1 - sizeRatio2), 0.15f) / 0.15f

            return ((ratioSim + sizeSim) / 2f).coerceIn(0f, 1f)
        }

        private fun compareContoursWeighted(
            contours1: List<FaceContour>,
            contours2: List<FaceContour>
        ): Float {
            if (contours1.isEmpty() || contours2.isEmpty()) return 0f

            var totalSimilarity = 0f
            var totalWeight = 0f

            val weights = mapOf(
                FaceContour.FACE to 2.0f,
                FaceContour.LEFT_EYE to 3.0f,
                FaceContour.RIGHT_EYE to 3.0f,
                FaceContour.NOSE_BRIDGE to 2.5f,
                FaceContour.NOSE_BOTTOM to 2.5f,
                FaceContour.LEFT_EYEBROW_TOP to 1.5f,
                FaceContour.RIGHT_EYEBROW_TOP to 1.5f,
                FaceContour.UPPER_LIP_TOP to 2.0f,
                FaceContour.LOWER_LIP_BOTTOM to 2.0f
            )

            for (contour1 in contours1) {
                val contour2 = contours2.find { it.faceContourType == contour1.faceContourType }
                val weight = weights[contour1.faceContourType] ?: 1.0f

                if (contour2 != null) {
                    val points1 = contour1.points
                    val points2 = contour2.points

                    if (points1.size == points2.size && points1.isNotEmpty()) {
                        var distanceSum = 0f
                        for (i in points1.indices) {
                            val dx = points1[i].x - points2[i].x
                            val dy = points1[i].y - points2[i].y
                            distanceSum += sqrt(dx * dx + dy * dy)
                        }

                        val avgDistance = distanceSum / points1.size
                        val maxDistance = 300f
                        val similarity = 1f - (avgDistance / maxDistance).coerceIn(0f, 1f)

                        totalSimilarity += similarity * weight
                        totalWeight += weight
                    }
                }
            }

            return if (totalWeight > 0) (totalSimilarity / totalWeight).coerceIn(0f, 1f) else 0f
        }

        private fun compareEyeRegions(
            face1: Face,
            face2: Face,
            bitmap1: Bitmap,
            bitmap2: Bitmap
        ): Float {
            val leftEye1 = face1.getLandmark(FaceLandmark.LEFT_EYE)
            val rightEye1 = face1.getLandmark(FaceLandmark.RIGHT_EYE)
            val leftEye2 = face2.getLandmark(FaceLandmark.LEFT_EYE)
            val rightEye2 = face2.getLandmark(FaceLandmark.RIGHT_EYE)

            if (leftEye1 == null || rightEye1 == null || leftEye2 == null || rightEye2 == null) {
                return 0.5f
            }

            try {
                val eyeSize = 40

                val leftEyeRegion1 = extractRegion(bitmap1, leftEye1.position.x.toInt(), leftEye1.position.y.toInt(), eyeSize)
                val rightEyeRegion1 = extractRegion(bitmap1, rightEye1.position.x.toInt(), rightEye1.position.y.toInt(), eyeSize)
                val leftEyeRegion2 = extractRegion(bitmap2, leftEye2.position.x.toInt(), leftEye2.position.y.toInt(), eyeSize)
                val rightEyeRegion2 = extractRegion(bitmap2, rightEye2.position.x.toInt(), rightEye2.position.y.toInt(), eyeSize)

                val leftEyeSim = if (leftEyeRegion1 != null && leftEyeRegion2 != null) {
                    compareHistograms(leftEyeRegion1, leftEyeRegion2)
                } else 0.5f

                val rightEyeSim = if (rightEyeRegion1 != null && rightEyeRegion2 != null) {
                    compareHistograms(rightEyeRegion1, rightEyeRegion2)
                } else 0.5f

                return ((leftEyeSim + rightEyeSim) / 2f).coerceIn(0f, 1f)

            } catch (e: Exception) {
                Log.e(TAG, "Error comparing eye regions", e)
                return 0.5f
            }
        }

        private fun extractRegion(bitmap: Bitmap, centerX: Int, centerY: Int, size: Int): Bitmap? {
            try {
                val left = (centerX - size / 2).coerceIn(0, bitmap.width - 1)
                val top = (centerY - size / 2).coerceIn(0, bitmap.height - 1)
                val width = size.coerceAtMost(bitmap.width - left)
                val height = size.coerceAtMost(bitmap.height - top)

                if (width <= 0 || height <= 0) return null

                return Bitmap.createBitmap(bitmap, left, top, width, height)
            } catch (e: Exception) {
                return null
            }
        }

        private fun cropFace(bitmap: Bitmap, bounds: android.graphics.Rect): Bitmap {
            val padding = 20
            val left = (bounds.left - padding).coerceAtLeast(0)
            val top = (bounds.top - padding).coerceAtLeast(0)
            val width = (bounds.width() + padding * 2).coerceAtMost(bitmap.width - left)
            val height = (bounds.height() + padding * 2).coerceAtMost(bitmap.height - top)

            return Bitmap.createBitmap(bitmap, left, top, width, height)
        }

        private fun compareHistograms(bitmap1: Bitmap, bitmap2: Bitmap): Float {
            val size = 100
            val resized1 = Bitmap.createScaledBitmap(bitmap1, size, size, true)
            val resized2 = Bitmap.createScaledBitmap(bitmap2, size, size, true)

            val hist1 = IntArray(256 * 3)
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

        private fun distance(p1: PointF, p2: PointF): Float {
            val dx = p1.x - p2.x
            val dy = p1.y - p2.y
            return sqrt(dx * dx + dy * dy)
        }

        // Extension function for Task.await()
        private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
            return suspendCancellableCoroutine { cont ->
                addOnSuccessListener { result ->
                    cont.resume(result, null)
                }
                addOnFailureListener { exception ->
                    cont.resumeWith(Result.failure(exception))
                }
            }
        }
    }
}
