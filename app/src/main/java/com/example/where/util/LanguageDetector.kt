package com.example.where.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Canvas
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import android.content.Context
import androidx.media3.common.C
import kotlinx.coroutines.*
import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.TextureView
import kotlin.coroutines.resumeWithException
import androidx.media3.common.PlaybackException

private const val TAG = "LangDetect"

class LanguageDetector(private val context: Context) {
    private val standardTextRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    private val chineseTextRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val languageIdentifier = LanguageIdentification.getClient()
    
    // Add rate limiting
    private var lastProcessedTime = 0L
    private val MIN_PROCESS_INTERVAL = 1000L // Minimum 1 second between processing attempts
    private val isProcessing = AtomicBoolean(false)

    data class LanguageResult(
        val languageCode: String,
        val confidence: Float,
        val displayName: String
    )

    data class VideoLanguageAnalysis(
        val detectedLanguages: Map<String, Int>, // language code to count of detections
        val confidence: Float,
        val primaryLanguage: String,
        val processedFrames: Int,
        val successfulDetections: Int,
        val errors: List<String>
    )

    private fun preprocessBitmap(originalBitmap: Bitmap): Bitmap? {
        try {
            val processedBitmap = Bitmap.createBitmap(
                originalBitmap.width,
                originalBitmap.height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(processedBitmap)
            val paint = Paint().apply {
                isAntiAlias = true
                isDither = true
                isFilterBitmap = true
            }
            
            // First pass: Enhance white text
            val whiteTextMatrix = ColorMatrix().apply {
                setSaturation(1.5f)
                postConcat(ColorMatrix(floatArrayOf(
                    2.0f, 0f, 0f, 0f, -50f,
                    0f, 2.0f, 0f, 0f, -50f,
                    0f, 0f, 2.0f, 0f, -50f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            paint.colorFilter = ColorMatrixColorFilter(whiteTextMatrix)
            canvas.drawBitmap(originalBitmap, 0f, 0f, paint)

            // Second pass: Invert colors to make white text dark
            val invertMatrix = ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
            paint.colorFilter = ColorMatrixColorFilter(invertMatrix)
            
            val tempBitmap = Bitmap.createBitmap(processedBitmap)
            val tempCanvas = Canvas(tempBitmap)
            tempCanvas.drawBitmap(processedBitmap, 0f, 0f, paint)

            // Third pass: Increase contrast of now-dark text
            val contrastMatrix = ColorMatrix().apply {
                setSaturation(2.0f)
                postConcat(ColorMatrix(floatArrayOf(
                    1.5f, 0f, 0f, 0f, -50f,
                    0f, 1.5f, 0f, 0f, -50f,
                    0f, 0f, 1.5f, 0f, -50f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            paint.colorFilter = ColorMatrixColorFilter(contrastMatrix)
            
            val finalBitmap = Bitmap.createBitmap(tempBitmap)
            val finalCanvas = Canvas(finalBitmap)
            finalCanvas.drawBitmap(tempBitmap, 0f, 0f, paint)

            // Clean up intermediate bitmaps
            processedBitmap.recycle()
            tempBitmap.recycle()
            
            return finalBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error preprocessing bitmap", e)
            return null
        }
    }

    suspend fun detectLanguageFromImage(bitmap: Bitmap): LanguageResult? = suspendCancellableCoroutine { continuation ->
        if (!isProcessing.compareAndSet(false, true)) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < MIN_PROCESS_INTERVAL) {
            isProcessing.set(false)
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        lastProcessedTime = currentTime

        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            isProcessing.set(false)
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        try {
            val processedBitmap = preprocessBitmap(bitmap)
            if (processedBitmap == null) {
                isProcessing.set(false)
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val image = InputImage.fromBitmap(processedBitmap, 0)
            
            standardTextRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isBlank()) {
                        tryChineseRecognizer(image, processedBitmap, continuation)
                    } else {
                        Log.d(TAG, "Detected text: '${visionText.text}'")
                        processedBitmap.recycle()
                        processDetectedText(visionText.text, continuation)
                    }
                }
                .addOnFailureListener { e ->
                    tryChineseRecognizer(image, processedBitmap, continuation)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in detectLanguageFromImage", e)
            isProcessing.set(false)
            continuation.resume(null)
        }
    }

    private fun tryChineseRecognizer(image: InputImage, processedBitmap: Bitmap, continuation: kotlinx.coroutines.CancellableContinuation<LanguageResult?>) {
        chineseTextRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                processedBitmap.recycle()
                
                if (visionText.text.isBlank()) {
                    isProcessing.set(false)
                    continuation.resume(null)
                    return@addOnSuccessListener
                }
                
                Log.d(TAG, "Detected text: '${visionText.text}'")
                processDetectedText(visionText.text, continuation)
            }
            .addOnFailureListener { e ->
                processedBitmap.recycle()
                isProcessing.set(false)
                continuation.resume(null)
            }
    }

    private fun processDetectedText(text: String, continuation: kotlinx.coroutines.CancellableContinuation<LanguageResult?>) {
        if (text.isBlank()) {
            isProcessing.set(false)
            continuation.resume(null)
            return
        }

        // Check for Korean characters first (Hangul)
        val hasHangul = text.any { it.toString().matches(Regex("[\uAC00-\uD7A3]")) }
        if (hasHangul) {
            Log.d(TAG, "Detected text: '$text' -> Korean")
            isProcessing.set(false)
            continuation.resume(LanguageResult("ko", 1.0f, "Korean"))
            return
        }

        // Then check for Japanese-specific characters (Hiragana and Katakana)
        val hasKana = text.any { it.toString().matches(Regex("[\u3040-\u309F\u30A0-\u30FF]")) }
        if (hasKana) {
            Log.d(TAG, "Detected text: '$text' -> Japanese")
            isProcessing.set(false)
            continuation.resume(LanguageResult("ja", 1.0f, "Japanese"))
            return
        }

        // Finally check for Chinese characters
        val hasHanzi = text.any { it.toString().matches(Regex("[\u4E00-\u9FFF]")) }
        if (hasHanzi && !hasKana) {  // If it has Hanzi but no Kana, it's likely Chinese
            Log.d(TAG, "Detected text: '$text' -> Chinese")
            isProcessing.set(false)
            continuation.resume(LanguageResult("zh", 1.0f, "Chinese"))
            return
        }

        // For non-Asian languages, use the language identifier
        languageIdentifier.identifyPossibleLanguages(text)
            .addOnSuccessListener { languages ->
                val mostLikelyLanguage = languages.maxByOrNull { it.confidence }
                if (mostLikelyLanguage != null && mostLikelyLanguage.confidence > 0.3f) {
                    Log.d(TAG, "Detected text: '$text' -> ${mostLikelyLanguage.languageTag} (${mostLikelyLanguage.confidence})")
                    isProcessing.set(false)
                    continuation.resume(
                        LanguageResult(
                            languageCode = mostLikelyLanguage.languageTag,
                            confidence = mostLikelyLanguage.confidence,
                            displayName = getLanguageDisplayName(mostLikelyLanguage.languageTag)
                        )
                    )
                } else {
                    isProcessing.set(false)
                    continuation.resume(null)
                }
            }
            .addOnFailureListener { e ->
                isProcessing.set(false)
                continuation.resume(null)
            }
    }

    private fun getLanguageDisplayName(languageCode: String): String {
        val displayName = when (languageCode) {
            "ar" -> "Arabic"
            "zh" -> "Chinese"
            "fr" -> "French"
            "de" -> "German"
            "hi" -> "Hindi"
            "it" -> "Italian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "es" -> "Spanish"
            else -> languageCode.uppercase()
        }
        return displayName
    }

    suspend fun analyzeVideoForLanguage(videoUrl: String): VideoLanguageAnalysis = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting video analysis for language detection: $videoUrl")
        
        val retriever = android.media.MediaMetadataRetriever()
        
        try {
            retriever.setDataSource(videoUrl, mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Connection" to "keep-alive"
            ))
            
            // Get video duration
            var duration = 0L
            try {
                val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                Log.d(TAG, "Raw duration metadata value: $durationStr")
                duration = durationStr?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                Log.e(TAG, "Error getting duration metadata: ${e.message}")
            }

            // Fallback: If duration is 0, try to determine it through frame extraction
            if (duration == 0L) {
                try {
                    Log.d(TAG, "Attempting to determine duration through frame extraction...")
                    // Try different time positions to find the video length
                    val timePositions = listOf(60000000L, 30000000L, 15000000L, 5000000L) // 60s, 30s, 15s, 5s in microseconds
                    
                    for (timePosition in timePositions) {
                        val testFrame = retriever.getFrameAtTime(timePosition, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (testFrame != null) {
                            duration = timePosition / 1000 // Convert microseconds to milliseconds
                            testFrame.recycle()
                            Log.d(TAG, "Video is at least ${duration/1000}s long based on frame extraction")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in fallback duration check: ${e.message}")
                }
            }
            
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            
            Log.d(TAG, "Video metadata - Duration: ${duration}ms, Dimensions: ${width}x${height}")
            
            if (width <= 0 || height <= 0) {
                throw Exception("Invalid video dimensions: ${width}x${height}")
            }

            // If we still have 0 duration, set a reasonable default
            if (duration == 0L) {
                duration = 30000L // Default to 30 seconds if we can't determine duration
                Log.d(TAG, "Using default duration of 30 seconds")
            }

            val detectedLanguages = mutableMapOf<String, Int>()
            var totalConfidence = 0f
            var processedFrames = 0
            var successfulDetections = 0
            val errors = mutableListOf<String>()

            val totalFrames = 15
            val durationSeconds = (duration / 1000).toInt().coerceAtLeast(1)
            val interval = durationSeconds.toFloat() / totalFrames
            
            Log.d(TAG, "Processing $totalFrames frames at ${interval}s intervals over ${durationSeconds}s video")

            for (frameIndex in 0 until totalFrames) {
                try {
                    val position = (frameIndex * interval * 1000).toLong() // Convert to milliseconds
                    val frame = retriever.getFrameAtTime(
                        position,  // Already in microseconds
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    
                    if (frame != null) {
                        processedFrames++
                        val result = processFrame(frame)
                        frame.recycle()
                        
                        if (result != null) {
                            successfulDetections++
                            detectedLanguages[result.languageCode] = 
                                detectedLanguages.getOrDefault(result.languageCode, 0) + 1
                            totalConfidence += result.confidence
                            
                            Log.d(TAG, "Frame $frameIndex: Detected ${result.displayName} with confidence ${result.confidence}")
                        }
                    } else {
                        Log.e(TAG, "Failed to extract frame at position ${position}ms")
                    }
                    
                    delay(100)
                    
                } catch (e: Exception) {
                    errors.add("Error processing frame $frameIndex: ${e.message}")
                }
            }
            
            val primaryLanguage = detectedLanguages.maxByOrNull { it.value }?.key ?: "unknown"
            val averageConfidence = if (successfulDetections > 0) 
                totalConfidence / successfulDetections else 0f

            Log.d(TAG, """
                Video analysis complete:
                Primary language: $primaryLanguage
                Average confidence: $averageConfidence
                Detected languages: $detectedLanguages
                Success rate: $successfulDetections/$processedFrames frames
            """.trimIndent())

            return@withContext VideoLanguageAnalysis(
                detectedLanguages = detectedLanguages,
                confidence = averageConfidence,
                primaryLanguage = primaryLanguage,
                processedFrames = processedFrames,
                successfulDetections = successfulDetections,
                errors = errors
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing video", e)
            throw e
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }

    private suspend fun processFrame(bitmap: Bitmap): LanguageResult? = suspendCancellableCoroutine { continuation ->
        try {
            val processedBitmap = preprocessBitmap(bitmap)
            if (processedBitmap == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val image = InputImage.fromBitmap(processedBitmap, 0)
            
            chineseTextRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!visionText.text.isBlank()) {
                        Log.d(TAG, "Chinese recognizer detected text: '${visionText.text}'")
                        processedBitmap.recycle()
                        processDetectedText(visionText.text, continuation)
                    } else {
                        standardTextRecognizer.process(image)
                            .addOnSuccessListener { standardText ->
                                processedBitmap.recycle()
                                if (!standardText.text.isBlank()) {
                                    Log.d(TAG, "Standard recognizer detected text: '${standardText.text}'")
                                    processDetectedText(standardText.text, continuation)
                                } else {
                                    continuation.resume(null)
                                }
                            }
                            .addOnFailureListener { e ->
                                processedBitmap.recycle()
                                continuation.resume(null)
                            }
                    }
                }
                .addOnFailureListener { e ->
                    processedBitmap.recycle()
                    continuation.resume(null)
                }
        } catch (e: Exception) {
            continuation.resume(null)
        }
    }
} 