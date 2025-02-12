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
        Log.d(TAG, "Starting bitmap preprocessing")
        Log.d(TAG, "Original bitmap: ${originalBitmap.width}x${originalBitmap.height}, config: ${originalBitmap.config}")
        
        try {
            val processedBitmap = Bitmap.createBitmap(
                originalBitmap.width,
                originalBitmap.height,
                Bitmap.Config.ARGB_8888
            )
            Log.d(TAG, "Created processed bitmap: ${processedBitmap.width}x${processedBitmap.height}")

            val canvas = Canvas(processedBitmap)
            val paint = Paint().apply {
                isAntiAlias = true
                isDither = true
                isFilterBitmap = true
            }
            
            // First pass: Enhance white text
            Log.d(TAG, "Applying white text enhancement")
            val whiteTextMatrix = ColorMatrix().apply {
                // Increase contrast
                setSaturation(1.5f)
                
                // Boost whites and darken darks
                postConcat(ColorMatrix(floatArrayOf(
                    2.0f, 0f, 0f, 0f, -50f,  // Red channel
                    0f, 2.0f, 0f, 0f, -50f,  // Green channel
                    0f, 0f, 2.0f, 0f, -50f,  // Blue channel
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            paint.colorFilter = ColorMatrixColorFilter(whiteTextMatrix)
            canvas.drawBitmap(originalBitmap, 0f, 0f, paint)
            Log.d(TAG, "Completed white text enhancement")

            // Second pass: Invert colors to make white text dark
            Log.d(TAG, "Applying color inversion")
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
            Log.d(TAG, "Completed color inversion")

            // Third pass: Increase contrast of now-dark text
            Log.d(TAG, "Applying final contrast adjustment")
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
            Log.d(TAG, "Completed final contrast adjustment")

            // Clean up intermediate bitmaps
            processedBitmap.recycle()
            tempBitmap.recycle()
            
            Log.d(TAG, "Successfully preprocessed bitmap")
            return finalBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error preprocessing bitmap", e)
            Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            return null
        }
    }

    suspend fun detectLanguageFromImage(bitmap: Bitmap): LanguageResult? = suspendCancellableCoroutine { continuation ->
        // Check if we're already processing
        if (!isProcessing.compareAndSet(false, true)) {
            Log.d(TAG, "Skipping detection - already processing")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        Log.d(TAG, "=== Starting new language detection attempt ===")
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < MIN_PROCESS_INTERVAL) {
            Log.d(TAG, "Skipping frame due to rate limiting (${currentTime - lastProcessedTime}ms since last process)")
            isProcessing.set(false)
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        lastProcessedTime = currentTime

        if (bitmap.isRecycled) {
            Log.e(TAG, "Input bitmap is recycled")
            isProcessing.set(false)
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        
        Log.d(TAG, "Input bitmap validation: ${bitmap.width}x${bitmap.height}, isRecycled=${bitmap.isRecycled}")
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            Log.e(TAG, "Invalid bitmap dimensions: ${bitmap.width}x${bitmap.height}")
            isProcessing.set(false)
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        try {
            Log.d(TAG, "Starting bitmap preprocessing")
            val processedBitmap = preprocessBitmap(bitmap)
            if (processedBitmap == null) {
                Log.e(TAG, "Preprocessing failed - null bitmap returned")
                isProcessing.set(false)
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            Log.d(TAG, "Preprocessing complete. Processed bitmap: ${processedBitmap.width}x${processedBitmap.height}")

            val image = InputImage.fromBitmap(processedBitmap, 0)
            Log.d(TAG, "Created InputImage from bitmap")
            
            Log.d(TAG, "Starting standard text recognition")
            standardTextRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    Log.d(TAG, "Standard text recognition completed")
                    if (visionText.text.isBlank()) {
                        Log.d(TAG, "No text detected with standard recognizer, trying Chinese recognizer")
                        tryChineseRecognizer(image, processedBitmap, continuation)
                    } else {
                        Log.d(TAG, "Text detected with standard recognizer: '${visionText.text}'")
                        Log.d(TAG, "Number of text blocks: ${visionText.textBlocks.size}")
                        visionText.textBlocks.forEachIndexed { index, block ->
                            Log.d(TAG, "Block $index: '${block.text}'")
                        }
                        processedBitmap.recycle()
                        processDetectedText(visionText.text, continuation)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Error with standard text recognition", e)
                    Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                    tryChineseRecognizer(image, processedBitmap, continuation)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in detectLanguageFromImage", e)
            Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            isProcessing.set(false)
            continuation.resume(null)
        }
    }

    private fun tryChineseRecognizer(image: InputImage, processedBitmap: Bitmap, continuation: kotlinx.coroutines.CancellableContinuation<LanguageResult?>) {
        Log.d(TAG, "Starting Chinese text recognition")
        chineseTextRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                Log.d(TAG, "Chinese text recognition completed")
                processedBitmap.recycle()
                
                if (visionText.text.isBlank()) {
                    Log.d(TAG, "No text detected with Chinese recognizer")
                    isProcessing.set(false)
                    continuation.resume(null)
                    return@addOnSuccessListener
                }
                
                Log.d(TAG, "Text detected with Chinese recognizer: '${visionText.text}'")
                Log.d(TAG, "Number of text blocks: ${visionText.textBlocks.size}")
                visionText.textBlocks.forEachIndexed { index, block ->
                    Log.d(TAG, "Block $index: '${block.text}'")
                }
                
                processDetectedText(visionText.text, continuation)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error with Chinese text recognition", e)
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                processedBitmap.recycle()
                isProcessing.set(false)
                continuation.resume(null)
            }
    }

    private fun processDetectedText(text: String, continuation: kotlinx.coroutines.CancellableContinuation<LanguageResult?>) {
        Log.d(TAG, "Processing detected text: '${text.take(100)}'")
        
        if (text.isBlank()) {
            Log.d(TAG, "Text is blank, nothing to process")
            isProcessing.set(false)
            continuation.resume(null)
            return
        }

        val hasKanji = text.any { it.toString().matches(Regex("[\u4E00-\u9FFF]")) }
        val hasKana = text.any { it.toString().matches(Regex("[\u3040-\u309F\u30A0-\u30FF]")) }
        
        Log.d(TAG, "Character analysis - hasKanji: $hasKanji, hasKana: $hasKana")

        if (hasKana) {
            Log.d(TAG, "Japanese characters (Kana) detected")
            isProcessing.set(false)
            continuation.resume(LanguageResult("ja", 1.0f, "Japanese"))
            return
        } else if (hasKanji) {
            Log.d(TAG, "Chinese characters (Kanji) detected")
            isProcessing.set(false)
            continuation.resume(LanguageResult("zh", 1.0f, "Chinese"))
            return
        }

        Log.d(TAG, "No Asian characters detected, using language identifier")
        languageIdentifier.identifyPossibleLanguages(text)
            .addOnSuccessListener { languages ->
                Log.d(TAG, "Language identification complete")
                Log.d(TAG, "Detected languages: ${languages.joinToString { "${it.languageTag}(${it.confidence})" }}")
                
                val mostLikelyLanguage = languages.maxByOrNull { it.confidence }
                if (mostLikelyLanguage != null && mostLikelyLanguage.confidence > 0.3f) {
                    Log.d(TAG, "Selected language: ${mostLikelyLanguage.languageTag} with confidence ${mostLikelyLanguage.confidence}")
                    isProcessing.set(false)
                    continuation.resume(
                        LanguageResult(
                            languageCode = mostLikelyLanguage.languageTag,
                            confidence = mostLikelyLanguage.confidence,
                            displayName = getLanguageDisplayName(mostLikelyLanguage.languageTag)
                        )
                    )
                } else {
                    Log.d(TAG, "No language met confidence threshold")
                    isProcessing.set(false)
                    continuation.resume(null)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error identifying language", e)
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
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
        Log.d(TAG, "Mapped language code '$languageCode' to display name '$displayName'")
        return displayName
    }

    suspend fun analyzeVideoForLanguage(videoUrl: String): VideoLanguageAnalysis = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting video analysis for language detection: $videoUrl")
        
        val retriever = android.media.MediaMetadataRetriever()
        
        try {
            // Set data source with timeout and user agent
            retriever.setDataSource(videoUrl, mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Connection" to "keep-alive"
            ))
            
            // Get video metadata
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            
            Log.d(TAG, "Video metadata - Duration: ${duration}ms, Dimensions: ${width}x${height}")
            
            if (width <= 0 || height <= 0) {
                throw Exception("Invalid video dimensions: ${width}x${height}")
            }

            // Analysis results
            val detectedLanguages = mutableMapOf<String, Int>()
            var totalConfidence = 0f
            var processedFrames = 0
            var successfulDetections = 0
            val errors = mutableListOf<String>()

            // Process frames every second
            val totalSeconds = (duration / 1000).toInt().coerceAtLeast(1)
            Log.d(TAG, "Processing ${totalSeconds} seconds of video")

            for (second in 0 until totalSeconds) {
                try {
                    val position = second * 1000L // Convert seconds to milliseconds
                    Log.d(TAG, "Extracting frame at ${second}s (${position}ms)")
                    
                    // Get frame at current position
                    val frame = retriever.getFrameAtTime(
                        position * 1000, // Convert to microseconds
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    
                    if (frame != null) {
                        processedFrames++
                        Log.d(TAG, "Successfully extracted frame at ${second}s")
                        
                        // Process frame
                        val result = processFrame(frame)
                        frame.recycle()
                        
                        if (result != null) {
                            successfulDetections++
                            detectedLanguages[result.languageCode] = 
                                detectedLanguages.getOrDefault(result.languageCode, 0) + 1
                            totalConfidence += result.confidence
                            
                            Log.d(TAG, "Frame at ${second}s: Detected ${result.displayName} with confidence ${result.confidence}")
                        } else {
                            Log.d(TAG, "Frame at ${second}s: No language detected")
                        }
                    } else {
                        val error = "Failed to extract frame at ${second}s"
                        Log.e(TAG, error)
                        errors.add(error)
                    }
                    
                    // Add a small delay between frame processing to avoid overwhelming the system
                    delay(100)
                    
                } catch (e: Exception) {
                    val error = "Error processing frame at ${second}s: ${e.message}"
                    Log.e(TAG, error, e)
                    errors.add(error)
                }
            }
            
            // Calculate results
            val primaryLanguage = detectedLanguages.maxByOrNull { it.value }?.key ?: "unknown"
            val averageConfidence = if (successfulDetections > 0) 
                totalConfidence / successfulDetections else 0f

            Log.d(TAG, """
                Video analysis complete:
                - Processed frames: $processedFrames
                - Successful detections: $successfulDetections
                - Primary language: $primaryLanguage
                - Average confidence: $averageConfidence
                - Detected languages: $detectedLanguages
                - Errors: ${errors.size}
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
            Log.d(TAG, "Processing frame: ${bitmap.width}x${bitmap.height}")
            
            val processedBitmap = preprocessBitmap(bitmap)
            if (processedBitmap == null) {
                Log.e(TAG, "Frame preprocessing failed")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val image = InputImage.fromBitmap(processedBitmap, 0)
            
            // Try Chinese recognizer first for Asian text
            chineseTextRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!visionText.text.isBlank()) {
                        Log.d(TAG, "Chinese recognizer detected text: '${visionText.text}'")
                        processedBitmap.recycle()
                        processDetectedText(visionText.text, continuation)
                    } else {
                        // Try standard recognizer
                        standardTextRecognizer.process(image)
                            .addOnSuccessListener { standardText ->
                                processedBitmap.recycle()
                                if (standardText.text.isBlank()) {
                                    Log.d(TAG, "No text detected in frame")
                                    continuation.resume(null)
                                } else {
                                    Log.d(TAG, "Standard recognizer detected text: '${standardText.text}'")
                                    processDetectedText(standardText.text, continuation)
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Standard text recognition failed", e)
                                processedBitmap.recycle()
                                continuation.resume(null)
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Chinese text recognition failed", e)
                    processedBitmap.recycle()
                    continuation.resume(null)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
            continuation.resume(null)
        }
    }
} 