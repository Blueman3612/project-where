package com.example.where.data.model

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val read: Boolean = false,
    val readAt: Long? = null,
    val readBy: String? = null
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "id" to id,
            "conversationId" to conversationId,
            "senderId" to senderId,
            "content" to content,
            "timestamp" to timestamp,
            "read" to read  // Always include read status
        )
        
        // Only add optional fields if they have values
        readAt?.let { map["readAt"] = it }
        readBy?.let { map["readBy"] = it }
        
        return map
    }

    companion object {
        fun fromMap(map: Map<String, Any>): Message? {
            return try {
                Message(
                    id = map["id"] as? String ?: return null,
                    conversationId = map["conversationId"] as? String ?: return null,
                    senderId = map["senderId"] as? String ?: return null,
                    content = map["content"] as? String ?: return null,
                    timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    read = map["read"] as? Boolean ?: false,
                    readAt = (map["readAt"] as? Number)?.toLong(),
                    readBy = map["readBy"] as? String
                )
            } catch (e: Exception) {
                null
            }
        }
    }
} 