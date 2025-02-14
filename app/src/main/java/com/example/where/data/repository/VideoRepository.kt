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
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import com.google.firebase.firestore.FieldValue
import kotlin.math.*

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
        
        // Weights for different factors in recommendation score
        private const val WEIGHT_RELEVANCE = 0.3f
        private const val WEIGHT_FRESHNESS = 0.2f
        private const val WEIGHT_POPULARITY = 0.15f
        private const val WEIGHT_DIFFICULTY = 0.15f
        private const val WEIGHT_DIVERSITY = 0.2f
    }

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
        val video = Video(
            id = videosCollection.document().id,
            url = url,
            thumbnailUrl = thumbnailUrl,
            location = location,
            title = title,
            description = description,
            authorId = authorId,
            authorUsername = authorUsername,
            source = source
        )
        
        try {
            videosCollection.document(video.id).set(video.toMap()).await()
            Log.d(TAG, "Successfully added video with id: ${video.id}")
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

    suspend fun toggleLike(videoId: String, userId: String): Boolean {
        try {
            // Get user's likes document
            val userLikesDoc = userLikesCollection.document(userId)
            val userLikesSnapshot = userLikesDoc.get().await()
            
            // Get current liked videos
            @Suppress("UNCHECKED_CAST")
            val currentLikedVideos = if (userLikesSnapshot.exists()) {
                (userLikesSnapshot.data?.get("likedVideos") as? List<String>) ?: listOf()
            } else {
                listOf()
            }

            // Toggle like
            val isLiked = currentLikedVideos.contains(videoId)
            val newLikedVideos = if (isLiked) {
                currentLikedVideos - videoId
            } else {
                currentLikedVideos + videoId
            }

            // Update user's likes
            userLikesDoc.set(
                mapOf(
                    "userId" to userId,
                    "likedVideos" to newLikedVideos
                )
            ).await()

            // Update video's like count
            val videoDoc = videosCollection.document(videoId)
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(videoDoc)
                val currentLikes = (snapshot.data?.get("likes") as? Number)?.toInt() ?: 0
                val newLikes = if (isLiked) currentLikes - 1 else currentLikes + 1
                transaction.update(videoDoc, "likes", newLikes)
            }.await()

            return !isLiked // Return new like state
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling like: ${e.message}")
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

    suspend fun getRecommendedVideo(userId: String): Video? {
        try {
            // Get user preferences
            val preferences = getUserPreferences(userId)
            
            // Get a batch of candidates (more efficient than scoring entire collection)
            val candidates = firestore.collection(VIDEOS_COLLECTION)
                .orderBy("likes", Query.Direction.DESCENDING)  // Use likes as a proxy for popularity
                .limit(50)
                .get()
                .await()
                .documents
                .mapNotNull { doc -> doc.data?.let { Video.fromMap(it) } }

            if (candidates.isEmpty()) {
                return getRandomVideo()
            }

            // Score each candidate
            val scoredVideos = candidates.map { video ->
                video to calculateRecommendationScore(video, preferences)
            }

            // Apply diversity boost to ensure variety
            val diversifiedScores = applyDiversityBoost(scoredVideos, userId)

            // Return the highest scoring video
            return diversifiedScores.maxByOrNull { it.second }?.first
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recommended video: ${e.message}")
            return getRandomVideo()
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
                createDefaultPreferences(userId)
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

    private fun calculateRecommendationScore(video: Video, preferences: UserPreferences): Float {
        // Relevance score based on user preferences
        val relevanceScore = calculateRelevanceScore(video, preferences)
        video.relevanceScore = relevanceScore
        
        // Freshness decay
        val freshness = calculateFreshnessScore(video.createdAt)
        video.freshness = freshness
        
        // Popularity signal
        val popularity = (video.likes + video.comments) / 100f
            .coerceIn(0f, 1f)
        
        // Difficulty matching (prefer videos slightly above user's skill level)
        val difficultyMatch = calculateDifficultyMatch(video, preferences.skillLevel)

        return (relevanceScore * WEIGHT_RELEVANCE +
                freshness * WEIGHT_FRESHNESS +
                popularity * WEIGHT_POPULARITY +
                difficultyMatch * WEIGHT_DIFFICULTY)
            .coerceIn(0f, 1f)
    }

    private fun calculateRelevanceScore(video: Video, preferences: UserPreferences): Float {
        var score = 0f
        var weights = 0f

        // Language preference
        preferences.preferredLanguages[video.primaryLanguage]?.let { weight ->
            score += weight
            weights += 1f
        }

        // Region preference (based on location)
        val region = getRegionFromLocation(video.location)
        preferences.preferredRegions[region]?.let { weight ->
            score += weight
            weights += 1f
        }

        // Category preference
        video.categories?.forEach { category ->
            preferences.preferredCategories[category]?.let { weight ->
                score += weight
                weights += 1f
            }
        }

        return if (weights > 0f) score / weights else 0.5f
    }

    private fun calculateFreshnessScore(timestamp: Long): Float {
        // Use integer division for days calculation
        val ageInDays = ((System.currentTimeMillis() - timestamp) / (24L * 60L * 60L * 1000L)).toInt()
        
        // Use a simple table-based approach with explicit Float values
        return when {
            ageInDays <= 0 -> 1.0f
            ageInDays >= 30 -> 0.0f
            else -> {
                val remainingDays = 30 - ageInDays
                (remainingDays * (1.0f / 30.0f))
            }
        }
    }

    private fun calculateDifficultyMatch(video: Video, userSkill: Float): Float {
        val videoDifficulty = video.difficulty ?: 0.5f
        // Prefer videos slightly above user's skill level
        val targetDifficulty = (userSkill + 0.1f).coerceIn(0f, 1f)
        return 1f - abs(videoDifficulty - targetDifficulty)
    }

    private suspend fun applyDiversityBoost(
        scoredVideos: List<Pair<Video, Float>>,
        userId: String
    ): List<Pair<Video, Float>> {
        // Get recently shown videos
        val recentVideos = getRecentlyShownVideos(userId)
        
        return scoredVideos.map { (video, score) ->
            // Apply penalty if similar to recently shown videos
            val diversityPenalty = calculateDiversityPenalty(video, recentVideos)
            video.diversityPenalty = diversityPenalty
            video to (score * (1f - WEIGHT_DIVERSITY * diversityPenalty))
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

    private fun calculateDiversityPenalty(video: Video, recentVideos: List<Video>): Float {
        if (recentVideos.isEmpty()) return 0f

        return recentVideos.map { recentVideo ->
            var similarity = 0f
            var factors = 0f

            // Language similarity
            if (video.primaryLanguage == recentVideo.primaryLanguage) {
                similarity += 1f
                factors += 1f
            }

            // Location proximity
            val distance = calculateDistance(video.location, recentVideo.location)
            if (distance < 100_000) { // Within 100km
                similarity += 1f - (distance.toFloat() / 100_000f)
                factors += 1f
            }

            // Category overlap
            val categoryOverlap = video.categories?.intersect(recentVideo.categories ?: emptySet())?.size ?: 0
            val totalCategories = video.categories?.size ?: 0
            if (totalCategories > 0) {
                similarity += categoryOverlap.toFloat() / totalCategories
                factors += 1f
            }

            if (factors > 0f) similarity / factors else 0f
        }.average().toFloat()
    }

    private fun calculateDistance(point1: LatLng, point2: LatLng): Double {
        val earthRadius = 6371000.0 // meters
        
        val lat1 = Math.toRadians(point1.latitude)
        val lon1 = Math.toRadians(point1.longitude)
        val lat2 = Math.toRadians(point2.latitude)
        val lon2 = Math.toRadians(point2.longitude)
        
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        
        val a = sin(dLat / 2).pow(2) + 
                cos(lat1) * cos(lat2) * 
                sin(dLon / 2).pow(2)
        
        val c = 2 * asin(sqrt(a))
        
        return earthRadius * c
    }

    private fun getRegionFromLocation(location: LatLng): String {
        // Simplified region calculation - could be replaced with proper reverse geocoding
        return when {
            location.latitude > 0 -> {
                when {
                    location.longitude < -30 -> "North America"
                    location.longitude < 60 -> "Europe"
                    else -> "Asia"
                }
            }
            else -> {
                when {
                    location.longitude < -30 -> "South America"
                    location.longitude < 60 -> "Africa"
                    else -> "Oceania"
                }
            }
        }
    }
} 