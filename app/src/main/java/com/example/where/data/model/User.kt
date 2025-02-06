package com.example.where.data.model

data class User(
    val id: String,
    val email: String,
    val username: String,
    val bio: String = "",
    val profilePictureUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "email" to email,
        "username" to username,
        "bio" to bio,
        "profilePictureUrl" to profilePictureUrl,
        "createdAt" to createdAt
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
                    createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
} 