package com.example.where.data.repository

import android.util.Log
import com.example.where.data.model.Comment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
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

            val comment = Comment(
                id = commentsCollection.document().id,
                videoId = videoId,
                authorId = currentUser.uid,
                authorUsername = username,
                text = text
            )

            commentsCollection.document(comment.id)
                .set(comment.toMap())
                .await()

            comment
        } catch (e: Exception) {
            Log.e(TAG, "Error adding comment: ${e.message}")
            null
        }
    }

    fun getCommentsForVideo(videoId: String): Flow<List<Comment>> = flow {
        try {
            val snapshot = commentsCollection
                .whereEqualTo("videoId", videoId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .await()

            val comments = snapshot.documents.mapNotNull { doc ->
                doc.data?.let { Comment.fromMap(it) }
            }
            emit(comments)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting comments: ${e.message}")
            emit(emptyList())
        }
    }.catch { e ->
        Log.e(TAG, "Error in comments flow: ${e.message}")
        emit(emptyList())
    }

    suspend fun deleteComment(commentId: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        
        return try {
            val comment = commentsCollection.document(commentId).get().await()
            if (comment.getString("authorId") == currentUser.uid) {
                commentsCollection.document(commentId).delete().await()
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