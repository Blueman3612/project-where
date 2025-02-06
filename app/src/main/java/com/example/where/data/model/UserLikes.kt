package com.example.where.data.model

data class UserLikes(
    val userId: String,
    val likedVideos: List<String> = listOf() // List of video IDs
) {
    fun toMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "likedVideos" to likedVideos
    )

    companion object {
        fun fromMap(map: Map<String, Any>): UserLikes? {
            val userId = map["userId"] as? String ?: return null
            @Suppress("UNCHECKED_CAST")
            val likedVideos = (map["likedVideos"] as? List<String>) ?: listOf()
            return UserLikes(userId, likedVideos)
        }
    }
} 