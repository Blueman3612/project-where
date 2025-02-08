package com.example.where.data.model

data class Comment(
    val id: String,
    val videoId: String,
    val authorId: String,
    val authorUsername: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val likes: Int = 0,
    val parentId: String? = null,  // null for top-level comments, set for replies
    val replyCount: Int = 0
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "videoId" to videoId,
        "authorId" to authorId,
        "authorUsername" to authorUsername,
        "text" to text,
        "timestamp" to timestamp,
        "likes" to likes,
        "replyCount" to replyCount
    ).plus(parentId?.let { mapOf("parentId" to it) } ?: emptyMap())

    companion object {
        fun fromMap(map: Map<String, Any>): Comment? {
            return try {
                val timestamp = when (val timestampValue = map["timestamp"]) {
                    is com.google.firebase.Timestamp -> timestampValue.toDate().time
                    is Number -> timestampValue.toLong()
                    else -> System.currentTimeMillis()
                }

                Comment(
                    id = map["id"] as String,
                    videoId = map["videoId"] as String,
                    authorId = map["authorId"] as String,
                    authorUsername = map["authorUsername"] as String,
                    text = map["text"] as String,
                    timestamp = timestamp,
                    likes = (map["likes"] as? Number)?.toInt() ?: 0,
                    parentId = map["parentId"] as? String,
                    replyCount = (map["replyCount"] as? Number)?.toInt() ?: 0
                )
            } catch (e: Exception) {
                null
            }
        }
    }
} 