package com.example.where.data.model

data class UserFollow(
    val followerId: String,
    val followingId: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "followerId" to followerId,
        "followingId" to followingId,
        "timestamp" to timestamp
    )

    companion object {
        fun fromMap(map: Map<String, Any>): UserFollow? {
            return try {
                UserFollow(
                    followerId = map["followerId"] as String,
                    followingId = map["followingId"] as String,
                    timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
} 