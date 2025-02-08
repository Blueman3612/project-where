package com.example.where.data.model

data class Conversation(
    val id: String,
    val participants: List<String>,
    val lastMessage: String?,
    val lastMessageTimestamp: Long?,
    val lastMessageSenderId: String?,
    val isApproved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "participants" to participants,
        "lastMessage" to (lastMessage ?: ""),
        "lastMessageTimestamp" to (lastMessageTimestamp ?: createdAt),
        "lastMessageSenderId" to (lastMessageSenderId ?: ""),
        "isApproved" to isApproved,
        "createdAt" to createdAt
    ).filterValues { it != "" }

    companion object {
        fun fromMap(map: Map<String, Any>): Conversation? {
            return try {
                Conversation(
                    id = map["id"] as? String ?: return null,
                    participants = (map["participants"] as? List<*>)?.mapNotNull { it as? String } ?: return null,
                    lastMessage = map["lastMessage"] as? String,
                    lastMessageTimestamp = (map["lastMessageTimestamp"] as? Number)?.toLong(),
                    lastMessageSenderId = map["lastMessageSenderId"] as? String,
                    isApproved = map["isApproved"] as? Boolean ?: false,
                    createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
} 