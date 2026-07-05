package com.example.albanianidverification.utils

import android.util.Log
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/**
 * Liveness Detector - Offline anti-spoofing using ML Kit
 * 
 * Detects if the person is physically present by requiring:
 * 1. Blink detection
 * 2. Smile detection  
 * 3. Head turn detection
 * 
 * This prevents using a static photo for verification.
 */
class LivenessDetector(
    private val onLivenessUpdate: (LivenessStatus) -> Unit,
    private val onLivenessComplete: (Boolean) -> Unit
) {
    
    companion object {
        private const val TAG = "LivenessDetector"
        
        // Thresholds
        private const val EYE_CLOSED_THRESHOLD = 0.3f
        private const val EYE_OPEN_THRESHOLD = 0.7f
        private const val SMILE_THRESHOLD = 0.7f
        private const val HEAD_TURN_THRESHOLD = 15f // degrees
        
        // Timing
        private const val BLINK_TIMEOUT_MS = 30000L // 30 seconds to blink
        private const val SMILE_TIMEOUT_MS = 30000L // 30 seconds to smile
        private const val HEAD_TURN_TIMEOUT_MS = 30000L // 30 seconds to turn head
    }
    
    // State tracking
    private var blinkDetected = false
    private var smileDetected = false
    private var headTurnDetected = false
    
    // Blink detection
    private var eyesWereClosed = false
    private var eyesOpenedAgain = false
    private var blinkStartTime = 0L
    
    // Smile detection
    private var wasNotSmiling = false
    private var smileStartTime = 0L
    
    // Head turn detection
    private var headWasCentered = false
    private var headTurned = false
    private var headTurnStartTime = 0L
    
    private var startTime = System.currentTimeMillis()

    // Latch: once liveness resolves (pass or fail) we must notify exactly once,
    // otherwise every subsequent camera frame re-fires the callback.
    private var completed = false

    /**
     * Process a detected face to check for liveness indicators
     */
    fun processFace(face: Face) {
        if (completed) return   // already notified — ignore further frames

        val currentTime = System.currentTimeMillis()

        // Check for blink
        if (!blinkDetected) {
            checkBlink(face, currentTime)
        }
        
        // Check for smile
        if (!smileDetected) {
            checkSmile(face, currentTime)
        }
        
        // Check for head turn
        if (!headTurnDetected) {
            checkHeadTurn(face, currentTime)
        }
        
        // Update status
        updateStatus()
        
        // Check if all checks passed — notify exactly once
        if (blinkDetected && smileDetected && headTurnDetected) {
            completed = true
            Log.d(TAG, "✓ All liveness checks passed!")
            onLivenessComplete(true)
            return
        }

        // Total timeout — notify failure exactly once
        if (currentTime - startTime > 60000L) { // 60 seconds total timeout
            completed = true
            Log.w(TAG, "✗ Liveness check timed out")
            onLivenessComplete(false)
        }
    }
    
    /**
     * Detect a blink (eyes close then open)
     */
    private fun checkBlink(face: Face, currentTime: Long) {
        val leftEyeOpen = face.leftEyeOpenProbability
        val rightEyeOpen = face.rightEyeOpenProbability
        
        if (leftEyeOpen == null || rightEyeOpen == null) return
        
        if (blinkStartTime == 0L) {
            blinkStartTime = currentTime
        }
        
        // Check if both eyes are closed
        val eyesClosed = leftEyeOpen < EYE_CLOSED_THRESHOLD && rightEyeOpen < EYE_CLOSED_THRESHOLD
        
        // Check if both eyes are open
        val eyesOpen = leftEyeOpen > EYE_OPEN_THRESHOLD && rightEyeOpen > EYE_OPEN_THRESHOLD
        
        if (eyesClosed && !eyesWereClosed) {
            eyesWereClosed = true
            Log.d(TAG, "Eyes closed detected")
        }
        
        if (eyesOpen && eyesWereClosed && !eyesOpenedAgain) {
            eyesOpenedAgain = true
            blinkDetected = true
            Log.d(TAG, "✓ Blink detected (eyes closed then opened)")
        }
        
        // Timeout check
        if (currentTime - blinkStartTime > BLINK_TIMEOUT_MS && !blinkDetected) {
            Log.w(TAG, "Blink timeout - user hasn't blinked")
        }
    }
    
    /**
     * Detect a smile
     */
    private fun checkSmile(face: Face, currentTime: Long) {
        val smilingProbability = face.smilingProbability ?: return
        
        if (smileStartTime == 0L) {
            smileStartTime = currentTime
        }
        
        // Not smiling
        if (smilingProbability < SMILE_THRESHOLD && !wasNotSmiling) {
            wasNotSmiling = true
            Log.d(TAG, "Neutral expression detected")
        }
        
        // Now smiling
        if (smilingProbability >= SMILE_THRESHOLD && wasNotSmiling) {
            smileDetected = true
            Log.d(TAG, "✓ Smile detected")
        }
        
        // Timeout check
        if (currentTime - smileStartTime > SMILE_TIMEOUT_MS && !smileDetected) {
            Log.w(TAG, "Smile timeout - user hasn't smiled")
        }
    }
    
    /**
     * Detect head turn (left or right)
     */
    private fun checkHeadTurn(face: Face, currentTime: Long) {
        val yaw = face.headEulerAngleY // Left/right rotation
        
        if (headTurnStartTime == 0L) {
            headTurnStartTime = currentTime
        }
        
        // Head is centered (looking straight)
        if (abs(yaw) < HEAD_TURN_THRESHOLD && !headWasCentered) {
            headWasCentered = true
            Log.d(TAG, "Head centered detected")
        }
        
        // Head turned left or right
        if (abs(yaw) > HEAD_TURN_THRESHOLD * 2 && headWasCentered && !headTurned) {
            headTurned = true
            headTurnDetected = true
            val direction = if (yaw > 0) "left" else "right"
            Log.d(TAG, "✓ Head turn detected ($direction, ${abs(yaw).toInt()}°)")
        }
        
        // Timeout check
        if (currentTime - headTurnStartTime > HEAD_TURN_TIMEOUT_MS && !headTurnDetected) {
            Log.w(TAG, "Head turn timeout - user hasn't turned head")
        }
    }
    
    /**
     * Update the liveness status and notify listeners
     */
    private fun updateStatus() {
        val status = LivenessStatus(
            blinkDetected = blinkDetected,
            smileDetected = smileDetected,
            headTurnDetected = headTurnDetected,
            instruction = getCurrentInstruction()
        )
        
        onLivenessUpdate(status)
    }
    
    /**
     * Get the current instruction for the user
     */
    private fun getCurrentInstruction(): String {
        return when {
            !blinkDetected -> "Please blink your eyes"
            !smileDetected -> "Now smile for the camera"
            !headTurnDetected -> "Turn your head left or right"
            else -> "All checks complete!"
        }
    }
    
    /**
     * Reset the liveness detector
     */
    fun reset() {
        blinkDetected = false
        smileDetected = false
        headTurnDetected = false
        
        eyesWereClosed = false
        eyesOpenedAgain = false
        blinkStartTime = 0L
        
        wasNotSmiling = false
        smileStartTime = 0L
        
        headWasCentered = false
        headTurned = false
        headTurnStartTime = 0L

        completed = false
        startTime = System.currentTimeMillis()   // restart the 60s window on every retry

        Log.d(TAG, "Liveness detector reset")
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        // Nothing to clean up currently
    }
    
    /**
     * Data class representing the current liveness status
     */
    data class LivenessStatus(
        val blinkDetected: Boolean,
        val smileDetected: Boolean,
        val headTurnDetected: Boolean,
        val instruction: String
    )
}
