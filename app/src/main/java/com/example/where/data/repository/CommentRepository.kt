package com.example.where.data.repository

import android.util.Log
import com.example.where.data.model.Comment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CommentRepository"

@Singleton
class CommentRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val commentsCollection = firestore.collection("comments")
    private val videosCollection = firestore.collection("videos")

    suspend fun addComment(videoId: String, text: String): Comment? {
        val currentUser = auth.currentUser ?: return null
        
        return try {
            // Get the user's username
            val userDoc = firestore.collection("users")
                .document(currentUser.uid)
                .get()
                .await()
            
            val username = userDoc.getString("username") 
                ?: currentUser.email?.substringBefore("@") 
                ?: return null

            // Create a new document reference first to get the ID
            val commentRef = commentsCollection.document()
            
            // Create the comment data with server timestamp
            val commentData = mapOf(
                "id" to commentRef.id,
                "videoId" to videoId,
                "authorId" to currentUser.uid,
                "authorUsername" to username,
                "text" to text,
                "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            // Use a transaction to update both the comment and the video's comment count
            firestore.runTransaction { transaction ->
                // Add the comment
                transaction.set(commentRef, commentData)
                
                // Increment the video's comment count
                val videoRef = videosCollection.document(videoId)
                transaction.update(videoRef, "comments", 
                    com.google.firebase.firestore.FieldValue.increment(1))
            }.await()

            // Create local comment object with current time for immediate display
            Comment(
                id = commentRef.id,
                videoId = videoId,
                authorId = currentUser.uid,
                authorUsername = username,
                text = text,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error adding comment: ${e.message}")
            null
        }
    }

    suspend fun getCommentsForVideo(videoId: String): List<Comment> {
        return try {
            Log.d(TAG, "Fetching comments for video: $videoId")
            val snapshot = commentsCollection
                .whereEqualTo("videoId", videoId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .await()

            Log.d(TAG, "Got ${snapshot.documents.size} comments from Firestore")
            
            snapshot.documents.mapNotNull { doc ->
                try {
                    Log.d(TAG, "Processing document: ${doc.id}")
                    Log.d(TAG, "Document data: ${doc.data}")
                    
                    val data = doc.data
                    if (data != null) {
                        // Ensure all required fields are present
                        val hasAllFields = data.containsKey("videoId") &&
                                         data.containsKey("authorId") &&
                                         data.containsKey("authorUsername") &&
                                         data.containsKey("text") &&
                                         data.containsKey("timestamp")
                        
                        if (!hasAllFields) {
                            Log.e(TAG, "Document ${doc.id} is missing required fields: $data")
                            return@mapNotNull null
                        }
                        
                        data["id"] = doc.id
                        val comment = Comment.fromMap(data)
                        Log.d(TAG, "Successfully parsed comment: $comment")
                        comment
                    } else {
                        Log.e(TAG, "Document ${doc.id} has null data")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing comment document ${doc.id}: ${e.message}")
                    e.printStackTrace()
                    null
                }
            }.also { comments ->
                Log.d(TAG, "Returning ${comments.size} comments")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting comments: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun deleteComment(commentId: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        
        return try {
            val comment = commentsCollection.document(commentId).get().await()
            if (comment.getString("authorId") == currentUser.uid) {
                val videoId = comment.getString("videoId") ?: return false
                
                // Use a transaction to update both the comment and the video's comment count
                firestore.runTransaction { transaction ->
                    // Delete the comment
                    transaction.delete(commentsCollection.document(commentId))
                    
                    // Decrement the video's comment count
                    val videoRef = videosCollection.document(videoId)
                    transaction.update(videoRef, "comments", 
                        com.google.firebase.firestore.FieldValue.increment(-1))
                }.await()
                
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting comment: ${e.message}")
            false
        }
    }
} 