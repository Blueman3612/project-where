package com.example.where.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.where.data.model.User
import com.example.where.data.model.UserFollow
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import java.util.UUID
import com.google.firebase.firestore.AggregateSource

private const val TAG = "UserRepository"

@Singleton
class UserRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val auth: FirebaseAuth,
    @ApplicationContext private val context: Context
) {
    private val usersCollection = firestore.collection("users")
    private val userFollowsCollection = firestore.collection("userFollows")

    suspend fun getUser(userId: String): User? {
        return try {
            val doc = usersCollection.document(userId).get().await()
            doc.data?.let { User.fromMap(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user: ${e.message}")
            null
        }
    }

    suspend fun createUser(email: String, username: String): User? {
        val userId = auth.currentUser?.uid ?: return null
        
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

    suspend fun updateUsername(userId: String, username: String): Boolean {
        return try {
            // First check if username is available
            if (!checkUsernameAvailable(username)) {
                return false
            }

            usersCollection.document(userId)
                .update("username", username)
                .await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating username: ${e.message}")
            false
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

    suspend fun generateTestUsers(count: Int = 50) {
        val firstNames = listOf(
            "James", "Emma", "Liam", "Olivia", "Noah", "Ava", "Oliver", "Isabella",
            "William", "Sophia", "Elijah", "Mia", "Lucas", "Charlotte", "Mason",
            "Amelia", "Logan", "Harper", "Sebastian", "Evelyn"
        )
        
        val lastNames = listOf(
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
            "Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez",
            "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin"
        )

        val usernameSuffixes = listOf(
            "gaming", "official", "real", "tv", "live", "original", "thereal",
            "", "", "", "" // Empty strings to make some usernames without suffixes
        )

        val usernamePrefixes = listOf(
            "the", "its", "im", "just", "mr", "ms", "dr",
            "", "", "", "" // Empty strings to make some usernames without prefixes
        )

        val bios = listOf(
            "Adventure seeker 🌎",
            "Living life one video at a time 📱",
            "Creating memories 📸",
            "Exploring the world ✈️",
            "Sharing my journey 🌟",
            "Making content that matters 🎥",
            "Travel enthusiast 🗺️",
            "Content creator 🎬",
            "Storyteller 📖",
            "Digital nomad 🌍"
        )

        try {
            repeat(count) {
                val firstName = firstNames.random()
                val lastName = lastNames.random()
                val prefix = usernamePrefixes.random()
                val suffix = usernameSuffixes.random()
                
                // Create username variations
                val username = buildString {
                    if (prefix.isNotEmpty()) append(prefix)
                    append(firstName.lowercase())
                    if (Random.nextBoolean()) append(Random.nextInt(10, 99))
                    if (suffix.isNotEmpty()) append("_$suffix")
                }
                
                // Create unique email
                val email = "${firstName.lowercase()}.${lastName.lowercase()}${Random.nextInt(100, 999)}@example.com"
                
                val user = User(
                    id = UUID.randomUUID().toString(),
                    email = email,
                    username = username,
                    bio = bios.random(),
                    createdAt = System.currentTimeMillis() - Random.nextLong(0, 30L * 24 * 60 * 60 * 1000) // Random date within last 30 days
                )

                // Check if username is available before creating
                if (checkUsernameAvailable(username)) {
                    usersCollection.document(user.id).set(user.toMap()).await()
                    Log.d(TAG, "Created test user: $username")
                } else {
                    Log.d(TAG, "Skipped duplicate username: $username")
                }
            }
            Log.d(TAG, "Finished generating test users")
        } catch (e: Exception) {
            Log.e(TAG, "Error generating test users: ${e.message}")
            throw e
        }
    }

    suspend fun searchUsers(query: String, limit: Int = 20): List<User> {
        return try {
            // Search by username first
            val usernameResults = usersCollection
                .orderBy("username")
                .startAt(query.lowercase())
                .endAt(query.lowercase() + '\uf8ff')
                .limit(limit.toLong())
                .get()
                .await()
                .documents
                .mapNotNull { doc -> 
                    doc.data?.let { data ->
                        // Get follower count for each user
                        val followerCount = userFollowsCollection
                            .whereEqualTo("followingId", doc.id)
                            .count()
                            .get(AggregateSource.SERVER)
                            .await()
                            .count
                            .toInt()
                        
                        // Add follower count to user data
                        val userData = data.toMutableMap()
                        userData["followerCount"] = followerCount
                        User.fromMap(userData)
                    }
                }

            // If we have enough results from username search, return them
            if (usernameResults.size >= limit) {
                return usernameResults
            }

            // Otherwise, also search by email
            val remainingLimit = limit - usernameResults.size
            val emailResults = usersCollection
                .orderBy("email")
                .startAt(query.lowercase())
                .endAt(query.lowercase() + '\uf8ff')
                .limit(remainingLimit.toLong())
                .get()
                .await()
                .documents
                .mapNotNull { doc -> 
                    doc.data?.let { data ->
                        // Get follower count for each user
                        val followerCount = userFollowsCollection
                            .whereEqualTo("followingId", doc.id)
                            .count()
                            .get(AggregateSource.SERVER)
                            .await()
                            .count
                            .toInt()
                        
                        // Add follower count to user data
                        val userData = data.toMutableMap()
                        userData["followerCount"] = followerCount
                        User.fromMap(userData)
                    }
                }
                .filter { user -> !usernameResults.any { it.id == user.id } } // Remove duplicates

            usernameResults + emailResults
        } catch (e: Exception) {
            Log.e(TAG, "Error searching users: ${e.message}")
            emptyList()
        }
    }

    suspend fun followUser(targetUserId: String): Boolean {
        val currentUserId = auth.currentUser?.uid ?: return false
        if (currentUserId == targetUserId) return false

        return try {
            // Create a unique ID for the follow relationship
            val followId = "$currentUserId-$targetUserId"
            
            val follow = UserFollow(
                followerId = currentUserId,
                followingId = targetUserId
            )
            
            userFollowsCollection.document(followId).set(follow.toMap()).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error following user: ${e.message}")
            false
        }
    }

    suspend fun unfollowUser(targetUserId: String): Boolean {
        val currentUserId = auth.currentUser?.uid ?: return false
        
        return try {
            val followId = "$currentUserId-$targetUserId"
            userFollowsCollection.document(followId).delete().await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error unfollowing user: ${e.message}")
            false
        }
    }

    suspend fun isFollowing(targetUserId: String): Boolean {
        val currentUserId = auth.currentUser?.uid ?: return false
        
        return try {
            val followId = "$currentUserId-$targetUserId"
            val doc = userFollowsCollection.document(followId).get().await()
            doc.exists()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking follow status: ${e.message}")
            false
        }
    }

    suspend fun getFollowerCount(userId: String): Int {
        return try {
            val snapshot = userFollowsCollection
                .whereEqualTo("followingId", userId)
                .count()
                .get(AggregateSource.SERVER)
                .await()
            
            snapshot.count.toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting follower count: ${e.message}")
            0
        }
    }

    suspend fun getFollowingCount(userId: String): Int {
        return try {
            val snapshot = userFollowsCollection
                .whereEqualTo("followerId", userId)
                .count()
                .get(AggregateSource.SERVER)
                .await()
            
            snapshot.count.toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting following count: ${e.message}")
            0
        }
    }
} 