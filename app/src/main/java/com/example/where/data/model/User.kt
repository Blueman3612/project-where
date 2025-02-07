package com.example.where.data.model

data class User(
    val id: String,
    val email: String,
    val username: String,
    val bio: String = "",
    val profilePictureUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val followerCount: Int = 0
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "email" to email,
        "username" to username,
        "bio" to bio,
        "profilePictureUrl" to profilePictureUrl,
        "createdAt" to createdAt,
        "followerCount" to followerCount
    ).filterValues { it != null }

    companion object {
        fun fromMap(map: Map<String, Any>): User? {
            return try {
                User(
                    id = map["id"] as String,
                    email = map["email"] as String,
                    username = map["username"] as String,
                    bio = (map["bio"] as? String) ?: "",
                    profilePictureUrl = map["profilePictureUrl"] as? String,
                    createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    followerCount = (map["followerCount"] as? Number)?.toInt() ?: 0
                )
            } catch (e: Exception) {
                null
            }
        }
    }
} 