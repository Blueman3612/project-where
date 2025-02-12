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

class LanguageDetector {
    private val standardTextRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    private val chineseTextRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val languageIdentifier = LanguageIdentification.getClient()
    
    // Add rate limiting
    private var lastProcessedTime = 0L
    private val MIN_PROCESS_INTERVAL = 1000L // Minimum 1 second between processing attempts

    data class LanguageResult(
        val languageCode: String,
        val confidence: Float,
        val displayName: String
    )

    private fun preprocessBitmap(originalBitmap: Bitmap): Bitmap? {
        try {
            // Create a new bitmap with the same dimensions
            val processedBitmap = Bitmap.createBitmap(
                originalBitmap.width,
                originalBitmap.height,
                Bitmap.Config.ARGB_8888
            )

            // Create a canvas to draw on the new bitmap
            val canvas = Canvas(processedBitmap)

            // Create a paint object with contrast and brightness adjustments
            val paint = Paint().apply {
                isAntiAlias = true
                isDither = true
                isFilterBitmap = true
            }
            
            // First pass: Convert to grayscale and enhance contrast
            val grayMatrix = ColorMatrix().apply {
                setSaturation(0f) // Convert to grayscale
                postConcat(ColorMatrix(floatArrayOf(
                    2f, 0f, 0f, 0f, -128f,
                    0f, 2f, 0f, 0f, -128f,
                    0f, 0f, 2f, 0f, -128f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            paint.colorFilter = ColorMatrixColorFilter(grayMatrix)
            canvas.drawBitmap(originalBitmap, 0f, 0f, paint)

            // Second pass: Enhance edges
            val edgeMatrix = ColorMatrix(floatArrayOf(
                -1f, -1f, -1f, 0f, 255f,
                -1f, 9f, -1f, 0f, 255f,
                -1f, -1f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
            paint.colorFilter = ColorMatrixColorFilter(edgeMatrix)
            
            val tempBitmap = Bitmap.createBitmap(processedBitmap)
            val tempCanvas = Canvas(tempBitmap)
            tempCanvas.drawBitmap(processedBitmap, 0f, 0f, paint)

            return tempBitmap
        } catch (e: Exception) {
            Log.e("LanguageDetector", "Error preprocessing bitmap", e)
            return null
        }
    }

    suspend fun detectLanguageFromImage(bitmap: Bitmap): LanguageResult? = suspendCancellableCoroutine { continuation ->
        // Check rate limiting
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < MIN_PROCESS_INTERVAL) {
            Log.d("LanguageDetector", "Skipping frame due to rate limiting")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        lastProcessedTime = currentTime

        // Validate bitmap
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            Log.e("LanguageDetector", "Invalid bitmap provided")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        Log.d("LanguageDetector", "Starting language detection on bitmap ${bitmap.width}x${bitmap.height}")
        
        try {
            // Create a copy and preprocess the bitmap
            val processedBitmap = preprocessBitmap(bitmap)
            if (processedBitmap == null) {
                Log.e("LanguageDetector", "Failed to preprocess bitmap")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val image = InputImage.fromBitmap(processedBitmap, 0)
            
            // Try Chinese recognizer first since we're seeing Chinese text
            chineseTextRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isBlank()) {
                        Log.d("LanguageDetector", "No text detected with Chinese recognizer, trying standard recognizer")
                        tryStandardRecognizer(image, processedBitmap, continuation)
                    } else {
                        Log.d("LanguageDetector", "Text detected with Chinese recognizer: ${visionText.text}")
                        processedBitmap.recycle()
                        processDetectedText(visionText.text, continuation)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("LanguageDetector", "Error with Chinese text recognition, trying standard recognizer", e)
                    tryStandardRecognizer(image, processedBitmap, continuation)
                }
        } catch (e: Exception) {
            Log.e("LanguageDetector", "Error creating InputImage", e)
            continuation.resume(null)
        }
    }

    private fun tryStandardRecognizer(image: InputImage, processedBitmap: Bitmap, continuation: kotlinx.coroutines.CancellableContinuation<LanguageResult?>) {
        standardTextRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                processedBitmap.recycle()
                if (visionText.text.isBlank()) {
                    Log.d("LanguageDetector", "No text detected with standard recognizer")
                    continuation.resume(null)
                    return@addOnSuccessListener
                }
                Log.d("LanguageDetector", "Text detected with standard recognizer: ${visionText.text}")
                processDetectedText(visionText.text, continuation)
            }
            .addOnFailureListener { e ->
                processedBitmap.recycle()
                Log.e("LanguageDetector", "Error with standard text recognition", e)
                continuation.resume(null)
            }
    }

    private fun processDetectedText(text: String, continuation: kotlinx.coroutines.CancellableContinuation<LanguageResult?>) {
        if (text.isBlank()) {
            Log.d("LanguageDetector", "No text to process")
            continuation.resume(null)
            return
        }

        Log.d("LanguageDetector", "Processing detected text: ${text.take(100)}...")

        // Check for Asian characters first
        val hasKanji = text.any { it.toString().matches(Regex("[\u4E00-\u9FFF]")) }
        val hasKana = text.any { it.toString().matches(Regex("[\u3040-\u309F\u30A0-\u30FF]")) }

        if (hasKana) {
            continuation.resume(LanguageResult("ja", 1.0f, "Japanese"))
            return
        } else if (hasKanji) {
            // If we see Kanji characters, we can be confident it's Chinese in this context
            continuation.resume(LanguageResult("zh", 1.0f, "Chinese"))
            return
        }

        // For Latin text, use language identifier
        languageIdentifier.identifyPossibleLanguages(text)
            .addOnSuccessListener { languages ->
                val mostLikelyLanguage = languages.maxByOrNull { it.confidence }
                if (mostLikelyLanguage != null && mostLikelyLanguage.confidence > 0.3f) {
                    continuation.resume(
                        LanguageResult(
                            languageCode = mostLikelyLanguage.languageTag,
                            confidence = mostLikelyLanguage.confidence,
                            displayName = getLanguageDisplayName(mostLikelyLanguage.languageTag)
                        )
                    )
                } else {
                    continuation.resume(null)
                }
            }
            .addOnFailureListener { e ->
                Log.e("LanguageDetector", "Error identifying language", e)
                continuation.resume(null)
            }
    }

    private fun getLanguageDisplayName(languageCode: String): String {
        return when (languageCode) {
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
    }
} 