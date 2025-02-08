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
    private val commentLikesCollection = firestore.collection("commentLikes")

    suspend fun addComment(videoId: String, text: String, parentId: String? = null): Comment? {
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
            val commentData = mutableMapOf(
                "id" to commentRef.id,
                "videoId" to videoId,
                "authorId" to currentUser.uid,
                "authorUsername" to username,
                "text" to text,
                "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "likes" to 0,
                "replyCount" to 0
            )
            
            // Add parentId if this is a reply
            parentId?.let { commentData["parentId"] = it }

            // Use a transaction to update both the comment and the counts
            firestore.runTransaction { transaction ->
                // Add the comment
                transaction.set(commentRef, commentData)
                
                // If this is a reply, increment the parent comment's reply count
                parentId?.let { pId ->
                    val parentRef = commentsCollection.document(pId)
                    transaction.update(parentRef, "replyCount", 
                        com.google.firebase.firestore.FieldValue.increment(1))
                }
                
                // Always increment the video's comment count, regardless of whether it's a reply or top-level comment
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
                timestamp = System.currentTimeMillis(),
                parentId = parentId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error adding comment: ${e.message}")
            null
        }
    }

    suspend fun getCommentsForVideo(videoId: String, parentId: String? = null): List<Comment> {
        return try {
            Log.d(TAG, "Fetching comments for video: $videoId with parentId: $parentId")
            
            // Use a simpler query and filter in memory
            val query = commentsCollection
                .whereEqualTo("videoId", videoId)
            
            Log.d(TAG, "Executing query...")
            val snapshot = query.get().await()
            Log.d(TAG, "Got ${snapshot.documents.size} comments from Firestore")
            
            if (snapshot.documents.isEmpty()) {
                Log.d(TAG, "No comments found in snapshot")
                return emptyList()
            }
            
            val comments = snapshot.documents.mapNotNull { doc ->
                try {
                    val data = doc.data
                    if (data != null) {
                        Log.d(TAG, "Processing comment document ${doc.id}: $data")
                        data["id"] = doc.id
                        // Only convert documents that match our parentId filter
                        val docParentId = data["parentId"] as? String
                        if (docParentId == parentId) {
                            Comment.fromMap(data)
                        } else {
                            null
                        }
                    } else {
                        Log.w(TAG, "Document ${doc.id} has no data")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing comment document ${doc.id}: ${e.message}")
                    e.printStackTrace()
                    null
                }
            }.sortedByDescending { it.timestamp } // Sort in memory
            
            Log.d(TAG, "Successfully parsed ${comments.size} comments")
            comments
        } catch (e: Exception) {
            Log.e(TAG, "Error getting comments: ${e.message}")
            Log.e(TAG, "Stack trace: ", e)
            emptyList()
        }
    }

    suspend fun getRepliesForComment(commentId: String): List<Comment> {
        val commentDoc = commentsCollection.document(commentId).get().await()
        val videoId = commentDoc.getString("videoId") ?: return emptyList()
        return getCommentsForVideo(videoId, commentId)
    }

    suspend fun toggleLike(commentId: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        val userId = currentUser.uid
        
        return try {
            val likeDoc = commentLikesCollection
                .document("${commentId}_${userId}")
                .get()
                .await()
            
            val isLiked = likeDoc.exists()
            
            firestore.runTransaction { transaction ->
                val commentRef = commentsCollection.document(commentId)
                val likeRef = commentLikesCollection.document("${commentId}_${userId}")
                
                if (isLiked) {
                    // Unlike
                    transaction.delete(likeRef)
                    transaction.update(commentRef, "likes", 
                        com.google.firebase.firestore.FieldValue.increment(-1))
                } else {
                    // Like
                    transaction.set(likeRef, mapOf(
                        "commentId" to commentId,
                        "userId" to userId,
                        "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    ))
                    transaction.update(commentRef, "likes", 
                        com.google.firebase.firestore.FieldValue.increment(1))
                }
            }.await()
            
            !isLiked // Return new like state
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling like: ${e.message}")
            false
        }
    }

    suspend fun isCommentLiked(commentId: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        return try {
            val likeDoc = commentLikesCollection
                .document("${commentId}_${currentUser.uid}")
                .get()
                .await()
            likeDoc.exists()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if comment is liked: ${e.message}")
            false
        }
    }

    suspend fun deleteComment(commentId: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        
        return try {
            val comment = commentsCollection.document(commentId).get().await()
            if (comment.getString("authorId") == currentUser.uid) {
                val videoId = comment.getString("videoId") ?: return false
                val parentId = comment.getString("parentId")
                
                // Fetch likes and replies before starting transaction
                val likes = commentLikesCollection
                    .whereEqualTo("commentId", commentId)
                    .get()
                    .await()
                
                val replies = if (parentId == null) {
                    commentsCollection
                        .whereEqualTo("parentId", commentId)
                        .get()
                        .await()
                } else null
                
                // Use a transaction to update all related counts
                firestore.runTransaction { transaction ->
                    // Delete the comment
                    transaction.delete(commentsCollection.document(commentId))
                    
                    // If this is a reply, decrement the parent comment's reply count
                    parentId?.let { pId ->
                        val parentRef = commentsCollection.document(pId)
                        transaction.update(parentRef, "replyCount", 
                            com.google.firebase.firestore.FieldValue.increment(-1))
                    }
                    
                    // Always decrement the video's comment count
                    val videoRef = videosCollection.document(videoId)
                    transaction.update(videoRef, "comments", 
                        com.google.firebase.firestore.FieldValue.increment(-1))
                    
                    // Delete all likes for this comment
                    likes.documents.forEach { likeDoc ->
                        transaction.delete(likeDoc.reference)
                    }
                    
                    // If this is a top-level comment, delete all replies and decrement video comment count for each reply
                    replies?.documents?.forEach { replyDoc ->
                        transaction.delete(replyDoc.reference)
                        // Decrement video comment count for each reply being deleted
                        transaction.update(videoRef, "comments",
                            com.google.firebase.firestore.FieldValue.increment(-1))
                    }
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