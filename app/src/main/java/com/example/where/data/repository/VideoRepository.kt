package com.example.where.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.where.data.model.Video
import com.example.where.data.model.VideoSource
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

private const val TAG = "VideoRepository"

@Singleton
class VideoRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    @ApplicationContext private val context: Context
) {
    private val videosCollection = firestore.collection("videos")
    private val userLikesCollection = firestore.collection("userLikes")
    
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

    suspend fun uploadVideo(
        videoUri: Uri,
        location: LatLng,
        title: String?,
        description: String?,
        authorId: String
    ): Video {
        Log.d(TAG, "Starting video upload from Uri: $videoUri")
        
        try {
            // Get the user's username
            val userDoc = firestore.collection("users").document(authorId).get().await()
            val authorUsername = userDoc.getString("username") ?: throw Exception("User not found")

            // Upload video to Firebase Storage with chunked upload
            val videoRef = storage.reference.child("videos/${System.currentTimeMillis()}_${authorId}.mp4")
            
            // Start upload with detailed error handling
            try {
                val stream = context.contentResolver.openInputStream(videoUri)
                    ?: throw IOException("Failed to open video stream")

                val metadata = StorageMetadata.Builder()
                    .setContentType("video/mp4")
                    .build()

                // Use putStream instead of putFile for better memory management
                val uploadTask = stream.use { inputStream ->
                    videoRef.putStream(inputStream, metadata)
                        .addOnProgressListener { taskSnapshot ->
                            val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount)
                            Log.d(TAG, "Upload progress: $progress%")
                        }
                        .await()
                }

                Log.d(TAG, "Video file uploaded successfully")
                
                val videoUrl = uploadTask.storage.downloadUrl.await().toString()
                Log.d(TAG, "Video URL retrieved: $videoUrl")

                // Generate and upload thumbnail
                val thumbnailUrl = generateAndUploadThumbnail(videoUri, authorId)

                // Add video metadata to Firestore
                return addVideo(
                    url = videoUrl,
                    location = location,
                    title = title,
                    description = description,
                    thumbnailUrl = thumbnailUrl,
                    authorId = authorId,
                    authorUsername = authorUsername,
                    source = VideoSource.USER_UPLOAD
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during video upload", e)
                when {
                    e.message?.contains("permission") == true -> 
                        throw Exception("Permission denied. Please check Firebase Storage rules.", e)
                    e.message?.contains("network") == true -> 
                        throw Exception("Network error during upload. Please check your connection.", e)
                    else -> throw Exception("Failed to upload video: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in uploadVideo", e)
            throw e
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

    suspend fun addRandomLikesToExistingVideos() {
        try {
            Log.d(TAG, "Starting to add random likes to videos...")
            val snapshot = videosCollection.get().await()
            Log.d(TAG, "Found ${snapshot.documents.size} videos to update")
            
            snapshot.documents.forEach { doc ->
                val currentData = doc.data
                if (currentData != null) {
                    // Only update if likes field doesn't exist or is 0
                    if (currentData["likes"] == null || (currentData["likes"] as? Number)?.toInt() == 0) {
                        val randomLikes = (1..1000).random()
                        Log.d(TAG, "Adding $randomLikes likes to video ${doc.id}")
                        
                        videosCollection.document(doc.id)
                            .update("likes", randomLikes)
                            .await()
                    }
                }
            }
            
            Log.d(TAG, "Successfully added random likes to all videos")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding random likes to videos: ${e.message}")
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
} 