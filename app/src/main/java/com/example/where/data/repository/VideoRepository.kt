package com.example.where.data.repository

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VideoRepository"

@Singleton
class VideoRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage
) {
    private val videosCollection = firestore.collection("videos")
    
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
            // Upload video to Firebase Storage
            val videoRef = storage.reference.child("videos/${System.currentTimeMillis()}_${authorId}.mp4")
            
            // Start upload with detailed error handling
            try {
                val uploadTask = videoRef.putFile(videoUri).await()
                Log.d(TAG, "Video file uploaded successfully")
                
                val videoUrl = uploadTask.storage.downloadUrl.await().toString()
                Log.d(TAG, "Video URL retrieved: $videoUrl")

                // Add video metadata to Firestore
                return addVideo(
                    url = videoUrl,
                    location = location,
                    title = title,
                    description = description,
                    authorId = authorId,
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
            
            return randomDoc.data?.let { data -> 
                Video.fromMap(data) ?: run {
                    Log.e(TAG, "Failed to parse video data, clearing invalid document")
                    // Optionally delete invalid document
                    videosCollection.document(randomDoc.id).delete()
                    null
                }
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
} 