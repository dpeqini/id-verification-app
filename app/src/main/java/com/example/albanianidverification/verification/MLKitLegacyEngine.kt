package com.example.albanianidverification.verification

import android.graphics.Bitmap
import com.example.albanianidverification.utils.FaceComparator

/**
 * Wraps the existing ML Kit–based FaceComparator so it plugs into the
 * new engine interface without touching the original code.
 */
class MLKitLegacyEngine : FaceVerificationEngine {

    override val displayName = "ML Kit (Legacy)"
    override val description = "Original landmark + histogram comparison. No embeddings."

    override suspend fun verify(referenceFace: Bitmap, capturedFace: Bitmap): VerificationResult {
        val start = System.currentTimeMillis()
        val similarity = FaceComparator.compareFaces(referenceFace, capturedFace)
        val elapsed = System.currentTimeMillis() - start

        return VerificationResult(
            similarity = similarity,
            isMatch = FaceComparator.isMatch(similarity),
            isBorderline = FaceComparator.isBorderline(similarity),
            threshold = FaceComparator.getThreshold(),
            borderlineThreshold = FaceComparator.getBorderlineThreshold(),
            engineName = displayName,
            details = "Took ${elapsed}ms  •  ML Kit landmarks + histograms"
        )
    }

    override fun release() { /* nothing to release */ }
}