package com.example.where.data.model

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val read: Boolean = false
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "conversationId" to conversationId,
        "senderId" to senderId,
        "content" to content,
        "timestamp" to timestamp,
        "read" to read
    )

    companion object {
        fun fromMap(map: Map<String, Any>): Message? {
            return try {
                Message(
                    id = map["id"] as? String ?: return null,
                    conversationId = map["conversationId"] as? String ?: return null,
                    senderId = map["senderId"] as? String ?: return null,
                    content = map["content"] as? String ?: return null,
                    timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    read = map["read"] as? Boolean ?: false
                )
            } catch (e: Exception) {
                null
            }
        }
    }
} 