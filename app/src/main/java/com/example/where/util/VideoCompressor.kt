package com.example.where.util

import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class VideoCompressor {
    companion object {
        private const val TAG = "VideoCompressor"
        private const val TARGET_SIZE_MB = 4L
        private const val MB = 1024 * 1024L
        private const val TIMEOUT_US = 10000L
        private const val TARGET_ASPECT_RATIO = 9f / 16f // Vertical video aspect ratio

        suspend fun compressVideo(context: Context, inputUri: Uri): Uri = withContext(Dispatchers.IO) {
            val inputPath = getRealPathFromUri(context, inputUri)
            val outputFile = createOutputFile(context)
            
            try {
                var quality = 1f
                var attempts = 0
                var compressed = false
                
                while (!compressed && attempts < 3) {
                    attempts++
                    try {
                        compressVideoWithQuality(context, inputPath, outputFile.absolutePath, quality)
                        
                        if (outputFile.length() <= TARGET_SIZE_MB * MB) {
                            compressed = true
                        } else {
                            quality *= 0.5f
                            Log.d(TAG, "File still too large (${outputFile.length() / MB}MB), reducing quality to $quality")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Compression attempt $attempts failed", e)
                        if (attempts == 3) throw e
                    }
                }
                
                if (!compressed) {
                    throw Exception("Failed to compress video to target size after $attempts attempts")
                }
                
                Uri.fromFile(outputFile)
            } catch (e: Exception) {
                Log.e(TAG, "Video compression failed", e)
                throw e
            }
        }

        private fun getRealPathFromUri(context: Context, uri: Uri): String {
            val inputStream = context.contentResolver.openInputStream(uri)
            val tempFile = createTempFile(context)
            inputStream?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return tempFile.absolutePath
        }

        private fun createTempFile(context: Context): File {
            val cacheDir = context.cacheDir
            return File.createTempFile("temp_video", ".mp4", cacheDir)
        }

        private fun createOutputFile(context: Context): File {
            val cacheDir = context.cacheDir
            return File.createTempFile("compressed_video", ".mp4", cacheDir)
        }

        private suspend fun compressVideoWithQuality(
            context: Context,
            inputPath: String,
            outputPath: String,
            quality: Float
        ) = withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            try {
                extractor.setDataSource(inputPath)
                
                // Find video track
                val videoTrackIndex = (0 until extractor.trackCount).find { trackIndex ->
                    val format = extractor.getTrackFormat(trackIndex)
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: throw IllegalArgumentException("No video track found")
                
                val inputFormat = extractor.getTrackFormat(videoTrackIndex)
                extractor.selectTrack(videoTrackIndex)
                
                // Calculate dimensions for 9:16 aspect ratio
                val inputWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
                val inputHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                
                // Calculate target dimensions maintaining 9:16 ratio
                val (targetWidth, targetHeight) = calculateTargetDimensions(inputWidth, inputHeight)
                
                // Create output format with reduced bitrate and new dimensions
                val outputFormat = MediaFormat.createVideoFormat(
                    inputFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc",
                    targetWidth,
                    targetHeight
                ).apply {
                    // Set bitrate based on quality (2Mbps * quality)
                    val targetBitrate = (2_000_000 * quality).toInt()
                    setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                }
                
                // Create encoder and decoder
                val encoder = MediaCodec.createEncoderByType(outputFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc")
                val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc")
                
                // Create a Surface for encoder input
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = encoder.createInputSurface()
                encoder.start()
                
                // Configure decoder to output to Surface
                val surfaceTexture = android.graphics.SurfaceTexture(0).apply {
                    setDefaultBufferSize(targetWidth, targetHeight)
                }
                val surface = android.view.Surface(surfaceTexture)
                decoder.configure(inputFormat, surface, null, 0)
                decoder.start()

                // Start encoding process
                var outputTrackIndex = -1
                var inputDone = false
                var outputDone = false
                
                val bufferInfo = MediaCodec.BufferInfo()
                
                try {
                    while (!outputDone) {
                        if (!inputDone) {
                            val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                            if (inputBufferIndex >= 0) {
                                val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                                val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                                
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(
                                        inputBufferIndex,
                                        0,
                                        0,
                                        0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(
                                        inputBufferIndex,
                                        0,
                                        sampleSize,
                                        extractor.sampleTime,
                                        0
                                    )
                                    extractor.advance()
                                }
                            }
                        }
                        
                        // Handle decoder output/encoder input via Surface
                        val decoderOutputInfo = MediaCodec.BufferInfo()
                        val decoderStatus = decoder.dequeueOutputBuffer(decoderOutputInfo, TIMEOUT_US)
                        if (decoderStatus >= 0) {
                            val render = decoderOutputInfo.size != 0
                            decoder.releaseOutputBuffer(decoderStatus, render)
                            if (render) {
                                surfaceTexture.updateTexImage()
                            }
                        }
                        
                        // Handle encoder output
                        val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (outputTrackIndex >= 0) {
                                throw RuntimeException("Format changed twice")
                            }
                            outputTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                        } else if (encoderStatus >= 0) {
                            val encodedData = encoder.getOutputBuffer(encoderStatus)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            
                            if (bufferInfo.size != 0) {
                                encodedData?.position(bufferInfo.offset)
                                encodedData?.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(outputTrackIndex, encodedData!!, bufferInfo)
                            }
                            
                            encoder.releaseOutputBuffer(encoderStatus, false)
                            
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                } finally {
                    decoder.stop()
                    decoder.release()
                    encoder.stop()
                    encoder.release()
                    surface.release()
                    surfaceTexture.release()
                }
                
            } catch (e: Exception) {
                try {
                    muxer.release()
                } catch (e2: Exception) {
                    Log.e(TAG, "Error releasing muxer", e2)
                }
                throw e
            }
        }

        private fun calculateTargetDimensions(width: Int, height: Int): Pair<Int, Int> {
            val currentAspectRatio = width.toFloat() / height.toFloat()
            
            return if (currentAspectRatio > TARGET_ASPECT_RATIO) {
                // Video is too wide, crop width
                val newWidth = (height * TARGET_ASPECT_RATIO).toInt()
                newWidth to height
            } else {
                // Video is too tall, crop height
                val newHeight = (width / TARGET_ASPECT_RATIO).toInt()
                width to newHeight
            }
        }
    }
} 