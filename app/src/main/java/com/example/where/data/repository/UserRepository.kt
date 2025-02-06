package com.example.where.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.where.data.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UserRepository"

@Singleton
class UserRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val auth: FirebaseAuth,
    @ApplicationContext private val context: Context
) {
    private val usersCollection = firestore.collection("users")

    suspend fun getUser(userId: String): User? {
        return try {
            val doc = usersCollection.document(userId).get().await()
            doc.data?.let { User.fromMap(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user: ${e.message}")
            null
        }
    }

    suspend fun createUser(email: String): User? {
        val userId = auth.currentUser?.uid ?: return null
        val username = email.substringBefore("@") // Default username from email
        
        val user = User(
            id = userId,
            email = email,
            username = username
        )

        return try {
            usersCollection.document(userId).set(user.toMap()).await()
            user
        } catch (e: Exception) {
            Log.e(TAG, "Error creating user: ${e.message}")
            null
        }
    }

    suspend fun updateProfile(
        bio: String? = null,
        profilePictureUri: Uri? = null
    ): User? {
        val userId = auth.currentUser?.uid ?: return null
        
        try {
            // Get current user data
            val userDoc = usersCollection.document(userId).get().await()
            val updates = mutableMapOf<String, Any>()
            
            // Handle profile picture upload if provided
            if (profilePictureUri != null) {
                val pictureRef = storage.reference.child("profile_pictures/$userId.jpg")
                context.contentResolver.openInputStream(profilePictureUri)?.use { stream ->
                    pictureRef.putStream(stream).await()
                    val downloadUrl = pictureRef.downloadUrl.await().toString()
                    updates["profilePictureUrl"] = downloadUrl
                }
            }
            
            // Add bio update if provided
            bio?.let { updates["bio"] = it }
            
            // Update or create user document
            if (userDoc.exists()) {
                // Update existing document
                if (updates.isNotEmpty()) {
                    usersCollection.document(userId).update(updates).await()
                }
            } else {
                // Create new document with all required fields
                val user = User(
                    id = userId,
                    email = auth.currentUser?.email ?: "",
                    username = auth.currentUser?.email?.substringBefore("@") ?: userId,
                    bio = bio ?: "",
                    profilePictureUrl = updates["profilePictureUrl"] as? String
                )
                usersCollection.document(userId).set(user.toMap()).await()
            }
            
            // Return updated user
            return getUser(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating profile: ${e.message}")
            return null
        }
    }

    suspend fun checkUsernameAvailable(username: String): Boolean {
        return try {
            val query = usersCollection
                .whereEqualTo("username", username)
                .get()
                .await()
            query.isEmpty
        } catch (e: Exception) {
            Log.e(TAG, "Error checking username availability: ${e.message}")
            false
        }
    }
} 