package com.example.where.data.repository

import android.util.Log
import com.example.where.data.model.Conversation
import com.example.where.data.model.Message
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MessageRepository"

@Singleton
class MessageRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    fun getConversations(userId: String): Flow<List<Conversation>> = callbackFlow {
        Log.d(TAG, "Loading conversations for user: $userId")
        
        val listener = firestore.collection("conversations")
            .whereArrayContains("participants", userId)
            .orderBy("lastMessageTimestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Error loading conversations", e)
                    return@addSnapshotListener
                }
                
                if (snapshot == null) {
                    Log.d(TAG, "No conversations snapshot")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                
                Log.d(TAG, "Received ${snapshot.documents.size} conversations")
                
                val conversations = snapshot.documents.mapNotNull { doc ->
                    try {
                        val data = doc.data
                        if (data == null) {
                            Log.w(TAG, "Conversation document ${doc.id} has no data")
                            return@mapNotNull null
                        }
                        
                        Conversation.fromMap(data)?.also { conv ->
                            Log.d(TAG, "Loaded conversation: ${conv.id} with participants: ${conv.participants}")
                        } ?: run {
                            Log.w(TAG, "Failed to parse conversation from data: $data")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing conversation ${doc.id}", e)
                        null
                    }
                }
                
                Log.d(TAG, "Successfully parsed ${conversations.size} conversations")
                trySend(conversations)
            }
        awaitClose { 
            Log.d(TAG, "Removing conversations listener")
            listener.remove() 
        }
    }

    fun getMessages(conversationId: String): Flow<List<Message>> = callbackFlow {
        Log.d(TAG, "Starting to listen for messages in conversation: $conversationId")
        
        val listener = firestore.collection("messages")
            .whereEqualTo("conversationId", conversationId)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Error listening to messages", e)
                    return@addSnapshotListener
                }
                
                if (snapshot == null) {
                    Log.d(TAG, "No messages snapshot")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                
                val messages = snapshot.documents.mapNotNull { doc ->
                    try {
                        Message.fromMap(doc.data ?: return@mapNotNull null)?.also { message ->
                            Log.d(TAG, "Received message: ${message.id}, read: ${message.read}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing message ${doc.id}", e)
                        null
                    }
                }
                
                Log.d(TAG, "Sending ${messages.size} messages to UI")
                trySend(messages)
            }
            
        awaitClose { 
            Log.d(TAG, "Stopping message listener for conversation: $conversationId")
            listener.remove() 
        }
    }

    suspend fun sendMessage(message: Message) {
        firestore.collection("messages").document(message.id)
            .set(message.toMap())
            .await()

        firestore.collection("conversations").document(message.conversationId)
            .update(
                mapOf(
                    "lastMessage" to message.content,
                    "lastMessageTimestamp" to message.timestamp,
                    "lastMessageSenderId" to message.senderId
                )
            )
            .await()
    }

    suspend fun createConversation(conversation: Conversation): String {
        val ref = firestore.collection("conversations").document()
        ref.set(conversation.copy(id = ref.id).toMap()).await()
        return ref.id
    }

    suspend fun approveConversation(conversationId: String) {
        firestore.collection("conversations").document(conversationId)
            .update("isApproved", true)
            .await()
    }

    suspend fun markMessagesAsRead(conversationId: String, userId: String) {
        try {
            Log.d(TAG, "Starting markMessagesAsRead for conversation: $conversationId, user: $userId")
            
            // First verify the user is a participant in the conversation
            val conversation = firestore.collection("conversations")
                .document(conversationId)
                .get()
                .await()

            val participants = conversation.data?.get("participants") as? List<*>
            if (!conversation.exists() || participants?.contains(userId) != true) {
                Log.e(TAG, "User $userId is not a participant in conversation $conversationId")
                return
            }
            Log.d(TAG, "Verified user $userId is a participant in conversation")

            // Get messages not sent by the current user
            val messages = firestore.collection("messages")
                .whereEqualTo("conversationId", conversationId)
                .whereNotEqualTo("senderId", userId)
                .get()
                .await()

            val unreadCount = messages.documents.count { doc ->
                !(doc.getBoolean("read") ?: false)
            }
            Log.d(TAG, "Found ${messages.size()} total messages, $unreadCount unread")
            
            // Update each message individually
            val currentTime = System.currentTimeMillis()
            var updatedCount = 0
            messages.documents.forEach { doc ->
                try {
                    val messageData = doc.data
                    val isRead = messageData?.get("read") as? Boolean ?: false
                    val readBy = messageData?.get("readBy") as? String
                    
                    if (!isRead || readBy == null) {
                        Log.d(TAG, "Updating message ${doc.id} - Current read: $isRead, readBy: $readBy")
                        val updates = mapOf(
                            "read" to true,
                            "readAt" to currentTime,
                            "readBy" to userId
                        )
                        doc.reference.update(updates).await()
                        updatedCount++
                        Log.d(TAG, "Successfully updated message ${doc.id}")
                    } else {
                        Log.d(TAG, "Skipping message ${doc.id} - already read by $readBy")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating message ${doc.id}", e)
                }
            }

            // Update conversation's last read status
            if (updatedCount > 0) {
                try {
                    Log.d(TAG, "Updating conversation last read status")
                    firestore.collection("conversations").document(conversationId)
                        .update(
                            mapOf(
                                "lastReadAt" to currentTime,
                                "lastReadBy" to userId
                            )
                        ).await()
                    Log.d(TAG, "Successfully updated conversation read status")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating conversation read status", e)
                }
            }
            
            Log.d(TAG, "Completed markMessagesAsRead - Updated $updatedCount messages")
        } catch (e: Exception) {
            Log.e(TAG, "Error in markMessagesAsRead", e)
        }
    }
} 