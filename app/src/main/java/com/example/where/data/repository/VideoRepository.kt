package com.example.where.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.where.data.model.Video
import com.example.where.data.model.VideoSource
import com.example.where.data.model.UserPreferences
import com.example.where.util.VideoCompressor
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import com.google.firebase.firestore.FieldValue
import kotlin.math.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

private const val TAG = "VideoRepository"

@Singleton
class VideoRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    @ApplicationContext private val context: Context
) {
    private val videosCollection = firestore.collection("videos")
    private val userLikesCollection = firestore.collection("userLikes")
    
    companion object {
        private const val VIDEOS_COLLECTION = "videos"
        private const val ENGAGEMENTS_COLLECTION = "engagements"
        private const val USER_PREFERENCES_COLLECTION = "user_preferences"
        
        // Queue settings
        private const val INITIAL_BATCH_SIZE = 10     // Increased from 3
        private const val BACKGROUND_BATCH_SIZE = 50  // Increased from 20
        private const val TARGET_QUEUE_SIZE = 15     // Increased from 10
        private const val MIN_QUEUE_SIZE = 5         // Increased from 3
    }

    // Centralized weights for recommendation system
    object RecommendationWeights {
        // Main component weights (must sum to 1.0)
        const val RELEVANCE = 0.75f      // Personalized relevance (location, language, categories)
        const val FRESHNESS = 0.1f       // How recent the video is
        const val POPULARITY = 0.01f      // Likes and comments
        const val DIFFICULTY = 0.04f      // Match with user's skill level
        const val DIVERSITY = 0.1f        // Penalty for similar videos

        // Weights within relevance score (must sum to 1.0)
        const val LOCATION = 0.94f        // Location matching and distance
        const val LANGUAGE = 0.05f        // Language preferences
        const val CATEGORY = 0.01f        // Category preferences

        // Distance decay factors (in meters)
        const val LOCATION_DECAY_DISTANCE = 3000000.0  // 3000km characteristic distance
        const val SIMILARITY_DECAY_DISTANCE = 10000.0   // 10km scale for similarity calculation
        
        // Time decay factors
        const val FRESHNESS_DECAY_RATE = 0.03  // Reduced from 0.05 for slower decay
    }

    private val metadataUpdateCache = mutableSetOf<String>()
    private var lastRegionUpdate = 0L
    private val videoQueue = mutableListOf<Video>()
    private var lastQueueUpdateTime = 0L
    private var isProcessingQueue = false
    private var backgroundProcessingJob: kotlinx.coroutines.Job? = null

    fun getVideosFlow(): Flow<List<Video>> = flow {
        try {
            val snapshot = videosCollection.get().await()
            Log.d(TAG, "Retrieved ${snapshot.documents.size} videos")
            val videos = snapshot.documents.mapNotNull { doc ->
                doc.data?.let { Video.fromMap(it) }
            }
            emit(videos)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting videos: ${e.message}")
            emit(emptyList())
        }
    }

    suspend fun addVideo(
        url: String,
        location: LatLng,
        title: String? = null,
        description: String? = null,
        thumbnailUrl: String? = null,
        authorId: String,
        authorUsername: String,
        source: VideoSource
    ): Video {
        Log.d(TAG, "Adding video: $title at location: $location")
        val region = getRegionFromLocation(location)
        val video = Video(
            id = videosCollection.document().id,
            url = url,
            thumbnailUrl = thumbnailUrl,
            location = location,
            region = region,  // Add region field
            title = title,
            description = description,
            authorId = authorId,
            authorUsername = authorUsername,
            source = source,
            primaryLanguage = "en",  // Default to English
            languageConfidence = 1.0f,
            categories = setOf("general"),
            difficulty = 0.5f  // Default medium difficulty
        )
        
        try {
            videosCollection.document(video.id).set(video.toMap()).await()
            Log.d(TAG, "Successfully added video with id: ${video.id} in region: ${video.region}")
            return video
        } catch (e: Exception) {
            Log.e(TAG, "Error adding video: ${e.message}")
            throw e
        }
    }

    suspend fun uploadVideo(videoUri: Uri, location: LatLng, userId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // First compress the video
            val compressedVideoUri = VideoCompressor.compressVideo(context, videoUri)
            
            // Generate a unique ID for the video
            val videoId = UUID.randomUUID().toString()
            
            // Upload the compressed video to Firebase Storage
            val videoRef = storage.reference.child("videos/$videoId.mp4")
            val uploadTask = videoRef.putFile(compressedVideoUri)
            
            // Wait for the upload to complete and get the download URL
            val downloadUrl = uploadTask.await().storage.downloadUrl.await().toString()

            // Get the user's username
            val userDoc = firestore.collection("users").document(userId).get().await()
            val authorUsername = userDoc.getString("username") ?: throw Exception("User not found")
            
            // Create the video document in Firestore
            val video = Video(
                id = videoId,
                url = downloadUrl,
                thumbnailUrl = null,
                location = location,
                title = null,
                description = null,
                authorId = userId,
                authorUsername = authorUsername,
                source = VideoSource.USER_UPLOAD,
                likes = 0,
                createdAt = System.currentTimeMillis(),
                comments = 0
            )
            
            firestore.collection("videos")
                .document(videoId)
                .set(video.toMap())
                .await()
            
            // Clean up the compressed video file
            context.contentResolver.delete(compressedVideoUri, null, null)
            
            Result.success(videoId)
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading video", e)
            Result.failure(e)
        }
    }

    private suspend fun generateAndUploadThumbnail(videoUri: Uri, authorId: String): String? {
        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            
            // Get the first frame
            val bitmap = retriever.getFrameAtTime(0)
            retriever.release()

            if (bitmap != null) {
                // Convert bitmap to bytes
                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                val data = baos.toByteArray()
                bitmap.recycle()

                // Upload thumbnail
                val thumbnailRef = storage.reference.child("thumbnails/${System.currentTimeMillis()}_${authorId}.jpg")
                thumbnailRef.putBytes(data).await()
                return thumbnailRef.downloadUrl.await().toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail: ${e.message}")
        }
        return null
    }

    private suspend fun extractThumbnail(retriever: android.media.MediaMetadataRetriever): android.graphics.Bitmap? {
        try {
            // Try to get video duration
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L
            Log.d(TAG, "Video duration: ${duration}ms")

            // Try different time positions throughout the video
            val timePositions = mutableListOf<Long>()
            timePositions.add(0L) // First frame
            
            if (duration > 0) {
                timePositions.addAll(listOf(
                    duration / 4,           // 25% of video
                    duration / 2,           // 50% of video
                    (duration * 3) / 4,     // 75% of video
                    duration - 1000         // 1 second before end
                ))
            } else {
                // If duration unknown, try fixed positions
                timePositions.addAll(listOf(
                    500000L,    // 0.5 seconds
                    1000000L,   // 1 second
                    2000000L,   // 2 seconds
                    5000000L,   // 5 seconds
                    10000000L   // 10 seconds
                ))
            }
            
            for (timePosition in timePositions) {
                try {
                    Log.d(TAG, "Attempting to extract frame at ${timePosition/1000.0}s")
                    
                    // Try both precise and non-precise frame extraction
                    var bitmap = retriever.getFrameAtTime(timePosition, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap == null) {
                        Log.d(TAG, "Trying without OPTION_CLOSEST at ${timePosition/1000.0}s")
                        bitmap = retriever.getFrameAtTime(timePosition)
                    }
                    
                    if (bitmap != null) {
                        // Verify the bitmap is valid
                        if (bitmap.width > 0 && bitmap.height > 0) {
                            Log.d(TAG, "Successfully extracted frame at ${timePosition/1000.0}s (${bitmap.width}x${bitmap.height})")
                            return bitmap
                        } else {
                            Log.e(TAG, "Invalid bitmap dimensions at ${timePosition/1000.0}s: ${bitmap.width}x${bitmap.height}")
                            bitmap.recycle()
                        }
                    } else {
                        Log.e(TAG, "No frame extracted at ${timePosition/1000.0}s")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting frame at ${timePosition/1000.0}s: ${e.message}")
                }
            }
            
            // If all attempts failed, try one last time with default options
            try {
                Log.d(TAG, "Attempting final frame extraction with default options")
                val bitmap = retriever.getFrameAtTime()
                if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                    Log.d(TAG, "Successfully extracted frame with default options (${bitmap.width}x${bitmap.height})")
                    return bitmap
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in final frame extraction attempt: ${e.message}")
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error in extractThumbnail: ${e.message}")
            return null
        }
    }

    suspend fun generateThumbnailForExistingVideo(videoUrl: String, videoId: String): String? {
        Log.d(TAG, "Starting thumbnail generation for video $videoId")
        
        // Check if user is authenticated
        if (FirebaseAuth.getInstance().currentUser == null) {
            Log.e(TAG, "Cannot generate thumbnail: User not authenticated")
            return null
        }
        
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Create a temporary file to store the video data
                val tempFile = java.io.File.createTempFile("video_", ".mp4", context.cacheDir)
                Log.d(TAG, "Created temp file: ${tempFile.absolutePath}")
                
                try {
                    // Download a larger portion of the video to the temp file
                    val connection = java.net.URL(videoUrl).openConnection() as java.net.HttpURLConnection
                    connection.setRequestProperty("Range", "bytes=0-1048576") // First 1MB to get more frames
                    connection.connectTimeout = 15000 // 15 seconds timeout
                    connection.readTimeout = 15000
                    
                    Log.d(TAG, "Downloading video segment from: $videoUrl")
                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    connection.disconnect()
                    Log.d(TAG, "Video segment downloaded successfully")

                    // Use the temp file for thumbnail generation
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(tempFile.absolutePath)
                    
                    // Log video metadata
                    try {
                        val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        Log.d(TAG, "Video metadata - Width: $width, Height: $height, Rotation: $rotation, Duration: ${duration}ms")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading video metadata: ${e.message}")
                    }
                    
                    // Try to extract thumbnail
                    val bitmap = extractThumbnail(retriever)
                    retriever.release()

                    if (bitmap != null) {
                        Log.d(TAG, "Successfully extracted thumbnail frame")
                        // Convert bitmap to bytes
                        val baos = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                        val data = baos.toByteArray()
                        bitmap.recycle()

                        // Upload thumbnail
                        val thumbnailRef = storage.reference.child("thumbnails/${videoId}.jpg")
                        Log.d(TAG, "Uploading thumbnail to Firebase Storage")
                        try {
                            thumbnailRef.putBytes(data).await()
                            val thumbnailUrl = thumbnailRef.downloadUrl.await().toString()
                            Log.d(TAG, "Thumbnail uploaded successfully: $thumbnailUrl")

                            try {
                                // Update video document with thumbnail URL
                                videosCollection.document(videoId)
                                    .update(mapOf(
                                        "thumbnailUrl" to thumbnailUrl
                                    ))
                                    .await()
                                Log.d(TAG, "Video document updated with thumbnail URL")
                                thumbnailUrl
                            } catch (e: Exception) {
                                Log.e(TAG, "Error updating Firestore document: ${e.message}", e)
                                if (e.message?.contains("permission") == true) {
                                    Log.e(TAG, "Permission denied. Please check Firestore rules for updating thumbnailUrl")
                                }
                                thumbnailUrl // Still return the URL even if Firestore update fails
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error uploading thumbnail: ${e.message}", e)
                            if (e.message?.contains("permission") == true) {
                                Log.e(TAG, "Permission denied. Please check Firebase Storage rules for /thumbnails/ folder")
                            }
                            null
                        }
                    } else {
                        Log.e(TAG, "Failed to extract thumbnail frame after trying multiple positions")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during thumbnail generation: ${e.message}", e)
                    throw e
                } finally {
                    // Clean up the temp file
                    if (tempFile.exists()) {
                        tempFile.delete()
                        Log.d(TAG, "Cleaned up temp file")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating thumbnail for video $videoId: ${e.message}", e)
                null
            }
        }
    }

    suspend fun getRandomVideo(): Video? {
        try {
            // Get all videos (this is okay for small collections)
            val snapshot = videosCollection.get().await()
            Log.d(TAG, "Retrieved ${snapshot.documents.size} videos")

            if (snapshot.documents.isEmpty()) {
                Log.d(TAG, "No videos found in collection")
                return null
            }

            // Select a random document
            val randomDoc = snapshot.documents.random()
            Log.d(TAG, "Selected random video with id: ${randomDoc.id}")
            Log.d(TAG, "Document data: ${randomDoc.data}")
            
            val data = randomDoc.data ?: return null
            
            // If authorUsername is missing, try to fetch it from the user document
            if (data["authorUsername"] == null && data["authorId"] != null) {
                try {
                    val authorId = data["authorId"] as String
                    val userDoc = firestore.collection("users").document(authorId).get().await()
                    val username = userDoc.getString("username") ?: "Unknown User"
                    
                    // Update only the authorUsername field
                    videosCollection.document(randomDoc.id)
                        .update(mapOf("authorUsername" to username))
                        .await()
                    
                    // Update the local data map
                    val updatedData = data.toMutableMap().apply {
                        put("authorUsername", username)
                    }
                    
                    return Video.fromMap(updatedData)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating authorUsername: ${e.message}")
                    // Continue with original data if update fails
                    return Video.fromMap(data)
                }
            }
            
            // Try to parse the video data
            return Video.fromMap(data) ?: run {
                Log.e(TAG, "Failed to parse video data for document ${randomDoc.id}")
                Log.e(TAG, "Document data: $data")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting random video: ${e.message}")
            throw e
        }
    }

    suspend fun clearAllVideos() {
        try {
            Log.d(TAG, "Starting to clear all videos...")
            val snapshot = videosCollection.get().await()
            Log.d(TAG, "Found ${snapshot.documents.size} videos to delete")
            
            // Delete each document and wait for completion
            snapshot.documents.forEach { doc ->
                Log.d(TAG, "Deleting video with id: ${doc.id}")
                videosCollection.document(doc.id).delete().await()
            }
            
            // Verify deletion
            val verifySnapshot = videosCollection.get().await()
            if (verifySnapshot.documents.isEmpty()) {
                Log.d(TAG, "Successfully cleared all videos")
            } else {
                Log.e(TAG, "Some videos remain after clearing: ${verifySnapshot.documents.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing videos: ${e.message}")
            throw e
        }
    }

    suspend fun addTestVideo() {
        Log.d(TAG, "Starting test video process...")
        try {
            // Clear existing videos first
            clearAllVideos()
            
            // Verify collection is empty before adding new videos
            val verifySnapshot = videosCollection.get().await()
            if (!verifySnapshot.documents.isEmpty()) {
                Log.e(TAG, "Collection not empty after clearing, aborting test video addition")
                return
            }
            
            // Get the video URL from Firebase Storage
            val videoRef = storage.reference.child("sample_videos/chicago_480p.mp4")
            val videoUrl = videoRef.downloadUrl.await().toString()
            Log.d(TAG, "Got video URL from Firebase Storage: $videoUrl")
            
            // Sample video with location
            val testVideo = Triple(
                videoUrl,
                LatLng(41.8513963, -87.6320782),  // Chicago location
                "Chicago"
            )

            Log.d(TAG, "Adding test video...")
            addVideo(
                url = testVideo.first,
                location = testVideo.second,
                title = testVideo.third,
                description = "A test video of Chicago",
                authorId = "test_author",
                authorUsername = "TestUser",
                source = VideoSource.PEXELS
            )
            Log.d(TAG, "Successfully added test video")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding test videos: ${e.message}")
            throw e
        }
    }

    suspend fun getUserUploadCount(userId: String): Int {
        try {
            val snapshot = videosCollection
                .whereEqualTo("authorId", userId)
                .whereEqualTo("source", VideoSource.USER_UPLOAD.name)
                .count()
                .get(AggregateSource.SERVER)
                .await()
            
            return snapshot.count.toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user upload count: ${e.message}")
            throw e
        }
    }

    private suspend fun toggleLikeInternal(videoId: String, userId: String): Boolean {
        try {
            val userLikesDoc = userLikesCollection.document(userId)
            val videoDoc = videosCollection.document(videoId)

            var newLikeState = false
            
            firestore.runTransaction { transaction ->
                val userLikes = transaction.get(userLikesDoc)
                @Suppress("UNCHECKED_CAST")
                val likedVideos = (userLikes.data?.get("likedVideos") as? List<String>) ?: listOf()
                
                newLikeState = !likedVideos.contains(videoId)
                
                // Update user's liked videos list
                val updatedLikedVideos = if (newLikeState) {
                    likedVideos + videoId
                } else {
                    likedVideos - videoId
                }
                
                // Update video's like count
                val video = transaction.get(videoDoc)
                val currentLikes = (video.getLong("likes") ?: 0).toInt()
                val updatedLikes = if (newLikeState) currentLikes + 1 else currentLikes - 1
                
                // Perform the updates
                transaction.set(
                    userLikesDoc,
                    mapOf("likedVideos" to updatedLikedVideos),
                    com.google.firebase.firestore.SetOptions.merge()
                )
                transaction.update(videoDoc, "likes", updatedLikes)
            }.await()
            
            return newLikeState
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling like: ${e.message}")
            throw e
        }
    }

    suspend fun toggleLike(videoId: String, userId: String): Boolean {
        try {
            val newLikeState = toggleLikeInternal(videoId, userId)
            if (newLikeState) {
                // Get the video and update preferences
                getVideo(videoId)?.let { video ->
                    updatePreferencesFromVideo(userId, video, "like")
                }
            }
            return newLikeState
        } catch (e: Exception) {
            Log.e(TAG, "Error in toggleLike: ${e.message}")
            throw e
        }
    }

    suspend fun isVideoLiked(videoId: String, userId: String): Boolean {
        try {
            val userLikesDoc = userLikesCollection.document(userId).get().await()
            if (!userLikesDoc.exists()) return false
            
            @Suppress("UNCHECKED_CAST")
            val likedVideos = (userLikesDoc.data?.get("likedVideos") as? List<String>) ?: listOf()
            return likedVideos.contains(videoId)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if video is liked: ${e.message}")
            throw e
        }
    }

    suspend fun getUserLikedVideos(userId: String): List<Video> {
        try {
            val userLikesDoc = userLikesCollection.document(userId).get().await()
            if (!userLikesDoc.exists()) return emptyList()
            
            @Suppress("UNCHECKED_CAST")
            val likedVideoIds = (userLikesDoc.data?.get("likedVideos") as? List<String>) ?: listOf()
            if (likedVideoIds.isEmpty()) return emptyList()
            
            val videos = videosCollection
                .whereIn(FieldPath.documentId(), likedVideoIds)
                .get()
                .await()
                .documents
                .mapNotNull { doc -> doc.data?.let { Video.fromMap(it) } }
            
            return videos
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user liked videos: ${e.message}")
            throw e
        }
    }

    fun getUserVideos(userId: String): Flow<List<Video>> = callbackFlow {
        try {
            // Initial emission
            val snapshot = videosCollection
                .whereEqualTo("authorId", userId)
                .get()
                .await()
            
            send(snapshot.documents.mapNotNull { doc -> 
                doc.data?.let { Video.fromMap(it) }
            })
            
            // Listen for real-time updates
            val registration = videosCollection
                .whereEqualTo("authorId", userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error listening to user videos: ${error.message}")
                        return@addSnapshotListener
                    }
                    
                    snapshot?.let { validSnapshot ->
                        val videos = validSnapshot.documents.mapNotNull { doc ->
                            doc.data?.let { Video.fromMap(it) }
                        }
                        trySend(videos)
                    }
                }
            
            // Clean up the listener when the flow is cancelled
            awaitClose {
                registration.remove()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user videos: ${e.message}")
            send(emptyList())
            close(e)
        }
    }

    suspend fun generateMissingThumbnails(): Int {
        var successCount = 0
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting batch thumbnail generation...")
                
                // Get all videos without thumbnails or with empty thumbnails
                val snapshot = videosCollection
                    .get()
                    .await()
                
                val videosNeedingThumbnails = snapshot.documents.filter { doc ->
                    val thumbnailUrl = doc.getString("thumbnailUrl")
                    thumbnailUrl == null || thumbnailUrl.isEmpty()
                }
                
                Log.d(TAG, "Found ${videosNeedingThumbnails.size} videos needing thumbnails")
                
                videosNeedingThumbnails.forEach { doc ->
                    try {
                        Log.d(TAG, "Processing document: ${doc.id}")
                        Log.d(TAG, "Document data: ${doc.data}")
                        
                        doc.data?.let { data ->
                            val video = Video.fromMap(data)
                            if (video != null) {
                                Log.d(TAG, "Generating thumbnail for video ${video.id} with URL: ${video.url}")
                                val thumbnailUrl = generateThumbnailForExistingVideo(video.url, video.id)
                                if (thumbnailUrl != null) {
                                    successCount++
                                    Log.d(TAG, "Successfully generated thumbnail $successCount/${videosNeedingThumbnails.size} - URL: $thumbnailUrl")
                                } else {
                                    Log.e(TAG, "Failed to generate thumbnail for video ${video.id}")
                                }
                            } else {
                                Log.e(TAG, "Failed to parse video data for document ${doc.id}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing video ${doc.id}: ${e.message}", e)
                        // Continue with next video even if one fails
                    }
                }
                
                Log.d(TAG, "Batch thumbnail generation completed. Success: $successCount/${videosNeedingThumbnails.size}")
                successCount
            } catch (e: Exception) {
                Log.e(TAG, "Error in batch thumbnail generation: ${e.message}", e)
                successCount
            }
        }
    }

    suspend fun migrateVideoCommentCounts() {
        try {
            Log.d(TAG, "Starting comment count migration...")
            // Get all videos
            val videos = videosCollection.get().await()
            Log.d(TAG, "Found ${videos.size()} videos to update")
            
            // For each video, count its comments and update the video document
            videos.documents.forEach { videoDoc ->
                try {
                    val videoId = videoDoc.id
                    
                    // Count comments for this video
                    val commentCount = firestore.collection("comments")
                        .whereEqualTo("videoId", videoId)
                        .count()
                        .get(AggregateSource.SERVER)
                        .await()
                        .count
                    
                    // Update the video document with the comment count
                    val updateData = mapOf("comments" to commentCount)
                    videosCollection.document(videoId)
                        .set(updateData, com.google.firebase.firestore.SetOptions.merge())
                        .await()
                    
                    Log.d(TAG, "Updated video $videoId with $commentCount comments")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating comment count for video ${videoDoc.id}: ${e.message}")
                }
            }
            Log.d(TAG, "Completed comment count migration for ${videos.size()} videos")
        } catch (e: Exception) {
            Log.e(TAG, "Error during comment count migration: ${e.message}")
            throw e
        }
    }

    suspend fun getVideo(videoId: String): Video? {
        return try {
            val doc = firestore.collection("videos")
                .document(videoId)
                .get()
                .await()
            
            if (!doc.exists()) {
                Log.e("VideoRepository", "Video $videoId not found")
                return null
            }

            val data = doc.data
            if (data != null) {
                Video.fromMap(data)
            } else {
                Log.e("VideoRepository", "No data for video $videoId")
                null
            }
        } catch (e: Exception) {
            Log.e("VideoRepository", "Error getting video $videoId", e)
            null
        }
    }

    suspend fun getAllVideos(): List<Video> {
        return try {
            firestore.collection("videos")
                .get()
                .await()
                .documents
                .mapNotNull { doc -> doc.data?.let { Video.fromMap(it) } }
                .also { videos ->
                    Log.d(TAG, "Retrieved ${videos.size} videos for processing")
                    videos.forEach { video ->
                        Log.d(TAG, "Video ${video.id}: primaryLanguage=${video.primaryLanguage}, confidence=${video.languageConfidence}")
                    }
                }
        } catch (e: Exception) {
            Log.e("VideoRepository", "Error getting all videos", e)
            emptyList()
        }
    }

    suspend fun updateVideoLanguage(videoId: String, language: String?, confidence: Float?) {
        try {
            Log.d(TAG, "Updating language for video $videoId - Language: $language, Confidence: $confidence")
            
            val updates = mutableMapOf<String, Any?>()
            
            // Only add non-null values to the update map
            if (language != null) {
                updates["primaryLanguage"] = language
            } else {
                updates["primaryLanguage"] = FieldValue.delete()
            }
            
            if (confidence != null) {
                updates["languageConfidence"] = confidence
            } else {
                updates["languageConfidence"] = FieldValue.delete()
            }
            
            updates["languageUpdatedAt"] = FieldValue.serverTimestamp()

            // Update Firestore document
            videosCollection.document(videoId)
                .set(updates, com.google.firebase.firestore.SetOptions.merge())
                .await()
            
            Log.d(TAG, "Successfully updated language fields for video $videoId")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating video language: ${e.message}", e)
            throw e
        }
    }

    suspend fun getNextVideo(userId: String): Video? {
        try {
            val now = System.currentTimeMillis()
            
            // Update regions if needed (hourly)
            if (now - lastRegionUpdate > 3600000L) {
                updateVideoRegions()
                lastRegionUpdate = now
            }
            
            // Start background processing if not already running
            startBackgroundProcessing(userId)
            
            // If queue is low, quickly replenish
            if (videoQueue.size < MIN_QUEUE_SIZE) {
                replenishQueueQuickly(userId)
            }
            
            // Return next video from queue if available
            return if (videoQueue.isNotEmpty()) {
                videoQueue.removeFirst()
            } else {
                // Emergency fallback
                getRandomVideo()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting next video: ${e.message}")
            return getRandomVideo()
        }
    }

    // Function to start background processing
    private fun startBackgroundProcessing(userId: String) {
        if (backgroundProcessingJob?.isActive == true) return
        
        backgroundProcessingJob = GlobalScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (videoQueue.size < TARGET_QUEUE_SIZE && !isProcessingQueue) {
                        replenishQueueInBackground(userId)
                    }
                    delay(5000) // Wait 5 seconds before checking queue again
                } catch (e: Exception) {
                    Log.e(TAG, "Error in background processing: ${e.message}")
                    delay(10000) // Wait longer if there was an error
                }
            }
        }
    }

    // Quick initial queue replenishment
    private suspend fun replenishQueueQuickly(userId: String) {
        if (isProcessingQueue) return
        
        try {
            isProcessingQueue = true
            
            val preferences = getUserPreferences(userId)
            val recentVideoIds = getRecentlyShownVideos(userId).map { it.id }
            
            // Get a mix of preferred and random regions
            val preferredRegions = preferences.preferredRegions.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
            
            // Get candidates from both preferred regions AND random videos
            val candidates = coroutineScope {
                val preferredCandidates = async {
                    if (preferredRegions.isNotEmpty()) {
                        firestore.collection(VIDEOS_COLLECTION)
                            .whereIn("region", preferredRegions)
                            .limit((INITIAL_BATCH_SIZE / 2).toLong())
                            .get()
                            .await()
                            .documents
                            .mapNotNull { doc -> doc.data?.let { Video.fromMap(it) } }
                    } else emptyList()
                }
                
                val randomCandidates = async {
                    firestore.collection(VIDEOS_COLLECTION)
                        .orderBy("createdAt", Query.Direction.DESCENDING)  // Get newer videos first
                        .limit(INITIAL_BATCH_SIZE.toLong())
                        .get()
                        .await()
                        .documents
                        .mapNotNull { doc -> doc.data?.let { Video.fromMap(it) } }
                }
                
                (preferredCandidates.await() + randomCandidates.await())
                    .distinctBy { it.id }
                    .filter { video -> !recentVideoIds.contains(video.id) }
            }
            
            // Quick scoring without diversity boost
            val scoredCandidates = candidates.map { video ->
                val score = calculateRecommendationScore(video, preferences)
                video to score
            }.sortedByDescending { it.second }
            
            // Add to queue
            videoQueue.addAll(scoredCandidates.map { it.first })
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in quick queue replenishment: ${e.message}")
        } finally {
            isProcessingQueue = false
        }
    }

    // Thorough background queue replenishment
    private suspend fun replenishQueueInBackground(userId: String) {
        if (isProcessingQueue) return
        
        try {
            isProcessingQueue = true
            
            val preferences = getUserPreferences(userId)
            val recentVideoIds = getRecentlyShownVideos(userId).map { it.id }
            
            // Get a diverse set of candidates
            val candidates = coroutineScope {
                val preferredRegionsCandidates = async {
                    val preferredRegions = preferences.preferredRegions.entries
                        .sortedByDescending { it.value }
                        .take(5)  // Increased from 3
                        .map { it.key }
                    
                    if (preferredRegions.isNotEmpty()) {
                        firestore.collection(VIDEOS_COLLECTION)
                            .whereIn("region", preferredRegions)
                            .limit((BACKGROUND_BATCH_SIZE / 3).toLong())
                            .get()
                            .await()
                            .documents
                            .mapNotNull { doc -> doc.data?.let { Video.fromMap(it) } }
                    } else emptyList()
                }
                
                val randomCandidates = async {
                    firestore.collection(VIDEOS_COLLECTION)
                        .orderBy("createdAt", Query.Direction.DESCENDING)  // Order by creation time
                        .limit((BACKGROUND_BATCH_SIZE * 2 / 3).toLong())
                        .get()
                        .await()
                        .documents
                        .mapNotNull { doc -> doc.data?.let { Video.fromMap(it) } }
                }
                
                (preferredRegionsCandidates.await() + randomCandidates.await())
                    .distinctBy { it.id }
                    .filter { video -> !recentVideoIds.contains(video.id) }
            }
            
            // Thorough scoring with diversity boost
            val scoredCandidates = candidates.map { video ->
                val score = calculateRecommendationScore(video, preferences)
                video to score
            }
            
            val diversifiedCandidates = applyDiversityBoost(scoredCandidates, userId)
            
            // Add to queue up to target size
            val spaceInQueue = TARGET_QUEUE_SIZE - videoQueue.size
            if (spaceInQueue > 0) {
                videoQueue.addAll(
                    diversifiedCandidates
                        .take(spaceInQueue)
                        .map { it.first }
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in background queue replenishment: ${e.message}")
        } finally {
            isProcessingQueue = false
        }
    }

    private suspend fun calculateRecommendationScore(video: Video, preferences: UserPreferences): Float {
        Log.d(TAG, "\nCalculating recommendation score for video ${video.id}")
        
        var languageScore = 0f
        var regionScore = 0f
        var categoryScore = 0f
        var difficultyMatch = 0f
        
        // Language score calculation
        video.primaryLanguage?.let { lang ->
            languageScore = preferences.preferredLanguages[lang] ?: 0f
            Log.d(TAG, "Language score for $lang: $languageScore")
        }

        // Simplified region score calculation - direct preference match
        val region = video.region ?: getRegionFromLocation(video.location)
        regionScore = preferences.preferredRegions[region] ?: 0.1f // Base score of 0.1 (10%) for non-preferred regions
        Log.d(TAG, "Region score for $region: $regionScore (preference: ${preferences.preferredRegions[region]})")

        // Category score calculation with minimum score
        video.categories?.let { categories ->
            if (categories.isNotEmpty()) {
                val categoryScores = categories.map { category ->
                    preferences.preferredCategories[category] ?: 0.1f  // Minimum score for any category
                }
                categoryScore = categoryScores.average().toFloat()
            }
        }

        // Rest of scoring calculations
        difficultyMatch = 1f - kotlin.math.abs(video.difficulty?.minus(preferences.skillLevel) ?: 0f)
        
        val ageInDays = (System.currentTimeMillis() - video.createdAt) / (24 * 60 * 60 * 1000)
        val freshness = kotlin.math.exp(-RecommendationWeights.FRESHNESS_DECAY_RATE * ageInDays).toFloat()
        
        val daysOld = maxOf(1L, ageInDays)
        val popularityScore = minOf(1f, (video.likes + video.comments) / (daysOld * 5f))  // Cap at 1.0

        // Calculate weighted component scores
        val weightedLocationScore = regionScore * RecommendationWeights.LOCATION
        val weightedLanguageScore = languageScore * RecommendationWeights.LANGUAGE
        val weightedCategoryScore = categoryScore * RecommendationWeights.CATEGORY
        
        video.relevanceScore = weightedLocationScore + weightedLanguageScore + weightedCategoryScore
        video.freshness = freshness
        
        video.componentScores = mapOf(
            "relevance" to video.relevanceScore,
            "freshness" to freshness,
            "popularity" to popularityScore,
            "difficultyMatch" to difficultyMatch,
            "location" to regionScore,
            "language" to languageScore,
            "category" to categoryScore
        )

        return (
            video.relevanceScore * RecommendationWeights.RELEVANCE +
            freshness * RecommendationWeights.FRESHNESS +
            popularityScore * RecommendationWeights.POPULARITY +
            difficultyMatch * RecommendationWeights.DIFFICULTY
        )
    }

    // Helper function to get a central location for a region
    private fun getLocationFromRegion(region: String): LatLng {
        val parts = region.removePrefix("r").split("_")
        if (parts.size == 2) {
            val latRegion = parts[0].toIntOrNull() ?: 0
            val lngRegion = parts[1].toIntOrNull() ?: 0
            
            // Convert region indices to coordinates
            // LAT: 0=90°S, 6=90°N in 30° increments
            // LNG: 0=180°W to 11=180°E in 30° increments
            val lat = ((latRegion.toDouble() * 30.0) - 90.0 + 15.0).coerceIn(-90.0, 90.0)
            val lng = ((lngRegion.toDouble() * 30.0) - 180.0 + 15.0).coerceIn(-180.0, 180.0)
            
            return LatLng(lat, lng)
        }
        return LatLng(0.0, 0.0)
    }

    private fun calculateVideoSimilarity(video1: Video, video2: Video): Double {
        var similarity = 0.0
        var factors = 0.0

        // Language similarity
        if (video1.primaryLanguage != null && video2.primaryLanguage != null) {
            similarity += if (video1.primaryLanguage == video2.primaryLanguage) 1.0 else 0.0
            factors += 1.0
        }

        // Location similarity (based on distance)
        val distance = calculateDistance(video1.location, video2.location)
        val locationSimilarity = kotlin.math.exp(-distance / RecommendationWeights.SIMILARITY_DECAY_DISTANCE)
        similarity += locationSimilarity
        factors += 1.0

        // Category similarity
        val categories1 = video1.categories ?: emptySet()
        val categories2 = video2.categories ?: emptySet()
        if (categories1.isNotEmpty() && categories2.isNotEmpty()) {
            val commonCategories = categories1.intersect(categories2).size
            val totalCategories = categories1.union(categories2).size
            similarity += commonCategories.toDouble() / totalCategories
            factors += 1.0
        }

        // Difficulty similarity
        if (video1.difficulty != null && video2.difficulty != null) {
            similarity += 1.0 - kotlin.math.abs(video1.difficulty - video2.difficulty)
            factors += 1.0
        }

        return if (factors > 0) similarity / factors else 0.0
    }

    private fun getRegionFromLocation(location: LatLng): String {
        // Convert lat/lng to region indices
        // LAT: -90 to 90 → 0 to 6
        // LNG: -180 to 180 → 0 to 11
        val latRegion = ((location.latitude + 90.0) / 30.0).toInt().coerceIn(0, 6)
        val lngRegion = ((location.longitude + 180.0) / 30.0).toInt().coerceIn(0, 11)
        
        return "r${latRegion}_${lngRegion}"
    }

    private fun calculateDistance(point1: LatLng, point2: LatLng): Double {
        val R = 6371e3 // Earth's radius in meters
        val φ1 = Math.toRadians(point1.latitude)
        val φ2 = Math.toRadians(point2.latitude)
        val Δφ = Math.toRadians(point2.latitude - point1.latitude)
        val Δλ = Math.toRadians(point2.longitude - point1.longitude)

        val a = kotlin.math.sin(Δφ/2) * kotlin.math.sin(Δφ/2) +
                kotlin.math.cos(φ1) * kotlin.math.cos(φ2) *
                kotlin.math.sin(Δλ/2) * kotlin.math.sin(Δλ/2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1-a))

        return R * c // Distance in meters
    }

    // Add function to update preferences based on user interactions
    suspend fun updatePreferencesFromVideo(userId: String, video: Video, interactionType: String) {
        try {
            val prefsDoc = firestore.collection(USER_PREFERENCES_COLLECTION).document(userId)
            
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(prefsDoc)
                val currentData = snapshot.data ?: mapOf()
                
                // Update language preferences
                val languagePrefs = (currentData["preferredLanguages"] as? Map<String, Double>) ?: emptyMap()
                val updatedLanguagePrefs = video.primaryLanguage?.let { lang ->
                    val currentPref = languagePrefs[lang] ?: 0.0
                    val boost = when(interactionType) {
                        "like" -> 0.2
                        "view" -> 0.1
                        else -> 0.05
                    }
                    languagePrefs + (lang to minOf(1.0, currentPref + boost))
                } ?: languagePrefs

                // Update region preferences
                val regionPrefs = (currentData["preferredRegions"] as? Map<String, Double>) ?: emptyMap()
                val region = getRegionFromLocation(video.location)
                val currentRegionPref = regionPrefs[region] ?: 0.0
                val regionBoost = when(interactionType) {
                    "like" -> 0.2
                    "view" -> 0.1
                    else -> 0.05
                }
                val updatedRegionPrefs = regionPrefs + (region to minOf(1.0, currentRegionPref + regionBoost))

                // Update category preferences
                val categoryPrefs = (currentData["preferredCategories"] as? Map<String, Double>) ?: emptyMap()
                val updatedCategoryPrefs = video.categories?.fold(categoryPrefs) { prefs, category ->
                    val currentPref = prefs[category] ?: 0.0
                    val boost = when(interactionType) {
                        "like" -> 0.2
                        "view" -> 0.1
                        else -> 0.05
                    }
                    prefs + (category to minOf(1.0, currentPref + boost))
                } ?: categoryPrefs

                // Update skill level based on video difficulty
                val currentSkill = (currentData["skillLevel"] as? Number)?.toDouble() ?: 0.5
                val updatedSkill = video.difficulty?.let { diff ->
                    val skillBoost = when(interactionType) {
                        "like" -> 0.1
                        else -> 0.05
                    }
                    (currentSkill + (diff - currentSkill) * skillBoost).coerceIn(0.0, 1.0)
                } ?: currentSkill

                // Update the document
                transaction.set(prefsDoc, mapOf(
                    "preferredLanguages" to updatedLanguagePrefs,
                    "preferredRegions" to updatedRegionPrefs,
                    "preferredCategories" to updatedCategoryPrefs,
                    "skillLevel" to updatedSkill,
                    "lastUpdated" to System.currentTimeMillis()
                ), com.google.firebase.firestore.SetOptions.merge())
            }.await()

            // Record the engagement
            firestore.collection(ENGAGEMENTS_COLLECTION)
                .add(mapOf(
                    "userId" to userId,
                    "videoId" to video.id,
                    "type" to interactionType,
                    "timestamp" to System.currentTimeMillis()
                ))
                .await()

        } catch (e: Exception) {
            Log.e(TAG, "Error updating preferences: ${e.message}")
        }
    }

    // Add function to record video view
    suspend fun recordVideoView(videoId: String, userId: String) {
        try {
            getVideo(videoId)?.let { video ->
                updatePreferencesFromVideo(userId, video, "view")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording video view: ${e.message}")
        }
    }

    private suspend fun applyDiversityBoost(
        scoredVideos: List<Pair<Video, Float>>,
        userId: String
    ): List<Pair<Video, Float>> {
        Log.d(TAG, "Applying diversity boost to ${scoredVideos.size} videos")
        
        // Get recently shown videos
        val recentVideos = getRecentlyShownVideos(userId)
        Log.d(TAG, "Found ${recentVideos.size} recent videos for diversity calculation")
        
        return scoredVideos.map { (video, score) ->
            // Calculate diversity penalty
            val diversityPenalty = if (recentVideos.isEmpty()) {
                0f
            } else {
                recentVideos.map { recentVideo ->
                    calculateVideoSimilarity(video, recentVideo).toFloat()
                }.average().toFloat()
            }
            
            video.diversityPenalty = diversityPenalty
            val adjustedScore = score * (1f - RecommendationWeights.DIVERSITY * diversityPenalty)
            Log.d(TAG, "Video ${video.id} - Original score: $score, Diversity penalty: $diversityPenalty, Final score: $adjustedScore")
            
            video to adjustedScore
        }
    }

    private suspend fun getRecentlyShownVideos(userId: String): List<Video> {
        return try {
            firestore.collection(ENGAGEMENTS_COLLECTION)
                .whereEqualTo("userId", userId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(20)
                .get()
                .await()
                .documents
                .mapNotNull { it.getString("videoId") }
                .mapNotNull { getVideo(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recent videos: ${e.message}")
            emptyList()
        }
    }

    suspend fun updateVideoMetadata(videoId: String) {
        // Skip if we've already updated this video's metadata in this session
        if (metadataUpdateCache.contains(videoId)) {
            return
        }

        try {
            val video = getVideo(videoId) ?: return
            
            // Only update if both categories and difficulty are null
            if (video.categories == null && video.difficulty == null) {
                videosCollection.document(videoId).update(mapOf(
                    "categories" to listOf("general"),
                    "difficulty" to 0.5f  // Default medium difficulty
                )).await()
                metadataUpdateCache.add(videoId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating video metadata: ${e.message}")
        }
    }

    suspend fun recordGuess(videoId: String, userId: String, isCorrect: Boolean) {
        try {
            // Record the guess engagement
            firestore.collection(ENGAGEMENTS_COLLECTION)
                .add(mapOf(
                    "userId" to userId,
                    "videoId" to videoId,
                    "type" to "guess",
                    "isCorrect" to isCorrect,
                    "timestamp" to System.currentTimeMillis()
                ))
                .await()

            // Update user preferences based on the guess
            getVideo(videoId)?.let { video ->
                updatePreferencesFromVideo(userId, video, "guess")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording guess: ${e.message}")
        }
    }

    private suspend fun getUserPreferences(userId: String): UserPreferences {
        return try {
            val doc = firestore.collection(USER_PREFERENCES_COLLECTION)
                .document(userId)
                .get()
                .await()
            
            if (doc.exists()) {
                val data = doc.data
                if (data != null) {
                    UserPreferences(
                        userId = userId,
                        preferredRegions = (data["preferredRegions"] as? Map<String, Double>)?.mapValues { it.value.toFloat() } ?: emptyMap(),
                        preferredLanguages = (data["preferredLanguages"] as? Map<String, Double>)?.mapValues { it.value.toFloat() } ?: emptyMap(),
                        preferredCategories = (data["preferredCategories"] as? Map<String, Double>)?.mapValues { it.value.toFloat() } ?: emptyMap(),
                        skillLevel = (data["skillLevel"] as? Number)?.toFloat() ?: 0.5f,
                        activeHours = (data["activeHours"] as? List<Int>) ?: List(24) { 1 },
                        lastUpdated = (data["lastUpdated"] as? Number)?.toLong() ?: System.currentTimeMillis()
                    )
                } else {
                    createDefaultPreferences(userId)
                }
            } else {
                val defaultPrefs = createDefaultPreferences(userId)
                // Store default preferences if none exist
                firestore.collection(USER_PREFERENCES_COLLECTION)
                    .document(userId)
                    .set(mapOf(
                        "userId" to userId,
                        "preferredRegions" to emptyMap<String, Double>(),
                        "preferredLanguages" to mapOf("en" to 0.5), // Start with slight English preference
                        "preferredCategories" to emptyMap<String, Double>(),
                        "skillLevel" to 0.5,
                        "activeHours" to List(24) { 1 },
                        "lastUpdated" to System.currentTimeMillis()
                    ))
                    .await()
                defaultPrefs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user preferences: ${e.message}")
            createDefaultPreferences(userId)
        }
    }

    private fun createDefaultPreferences(userId: String) = UserPreferences(
        userId = userId,
        preferredRegions = emptyMap(),
        preferredLanguages = emptyMap(),
        preferredCategories = emptyMap(),
        skillLevel = 0.5f,
        activeHours = List(24) { 1 },
        lastUpdated = System.currentTimeMillis()
    )

    // Add a function to update regions for existing videos
    suspend fun updateVideoRegions() {
        try {
            Log.d(TAG, "Starting region update for all videos...")
            val videos = getAllVideos()
            
            videos.forEach { video ->
                val region = getRegionFromLocation(video.location)
                if (video.region != region) {
                    videosCollection.document(video.id)
                        .update(mapOf("region" to region))
                        .await()
                    Log.d(TAG, "Updated region for video ${video.id} to $region")
                }
            }
            Log.d(TAG, "Completed region update for ${videos.size} videos")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating video regions: ${e.message}")
        }
    }

    private suspend fun getRecommendedVideo(userId: String): Video? {
        try {
            Log.d(TAG, "Getting recommended video for user $userId")
            
            // Get user preferences
            val preferences = getUserPreferences(userId)
            
            // Get recently viewed videos to avoid repeats
            val recentVideoIds = firestore.collection(ENGAGEMENTS_COLLECTION)
                .whereEqualTo("userId", userId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(10)
                .get()
                .await()
                .documents
                .mapNotNull { it.getString("videoId") }
            
            // Get preferred regions
            val preferredRegions = preferences.preferredRegions.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
            
            // First try to get videos from preferred regions
            var candidates = if (preferredRegions.isNotEmpty()) {
                firestore.collection(VIDEOS_COLLECTION)
                    .whereIn("region", preferredRegions)
                    .limit(10)
                    .get()
                    .await()
                    .documents
                    .mapNotNull { doc -> doc.data?.let { Video.fromMap(it) } }
                    .filter { video -> !recentVideoIds.contains(video.id) }
            } else emptyList()

            // If no candidates from preferred regions, get any videos
            if (candidates.isEmpty()) {
                candidates = firestore.collection(VIDEOS_COLLECTION)
                    .limit(5)
                    .get()
                    .await()
                    .documents
                    .mapNotNull { doc -> doc.data?.let { Video.fromMap(it) } }
                    .filter { video -> !recentVideoIds.contains(video.id) }
            }

            // Score candidates and apply diversity boost
            val scoredCandidates = candidates.map { video ->
                val score = calculateRecommendationScore(video, preferences)
                video to score
            }
            
            val diversityBoostedCandidates = applyDiversityBoost(scoredCandidates, userId)
            
            // Return the highest scoring candidate
            return diversityBoostedCandidates.maxByOrNull { (_, score) -> score }?.first
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recommended video: ${e.message}")
            return null
        }
    }
} 