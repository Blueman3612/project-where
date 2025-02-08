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
        val listener = firestore.collection("messages")
            .whereEqualTo("conversationId", conversationId)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    return@addSnapshotListener
                }
                val messages = snapshot?.documents?.mapNotNull { doc ->
                    Message.fromMap(doc.data ?: return@mapNotNull null)
                } ?: emptyList()
                trySend(messages)
            }
        awaitClose { listener.remove() }
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
        val batch = firestore.batch()
        val messages = firestore.collection("messages")
            .whereEqualTo("conversationId", conversationId)
            .whereNotEqualTo("senderId", userId)
            .whereEqualTo("read", false)
            .get()
            .await()

        messages.documents.forEach { doc ->
            batch.update(doc.reference, "read", true)
        }
        batch.commit().await()
    }
} 