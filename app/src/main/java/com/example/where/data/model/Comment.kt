package com.example.where.data.model

data class Comment(
    val id: String,
    val videoId: String,
    val authorId: String,
    val authorUsername: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "videoId" to videoId,
        "authorId" to authorId,
        "authorUsername" to authorUsername,
        "text" to text,
        "timestamp" to timestamp
    )

    companion object {
        fun fromMap(map: Map<String, Any>): Comment? {
            return try {
                Comment(
                    id = map["id"] as String,
                    videoId = map["videoId"] as String,
                    authorId = map["authorId"] as String,
                    authorUsername = map["authorUsername"] as String,
                    text = map["text"] as String,
                    timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
} 