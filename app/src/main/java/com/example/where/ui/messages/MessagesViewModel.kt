package com.example.where.ui.messages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.where.data.model.Conversation
import com.example.where.data.model.Message
import com.example.where.data.repository.MessageRepository
import com.example.where.data.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val auth: FirebaseAuth
) : ViewModel() {
    private val _selectedConversation = MutableStateFlow<Conversation?>(null)
    val selectedConversation = _selectedConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations = _conversations.asStateFlow()

    private val _usernames = MutableStateFlow<Map<String, String>>(emptyMap())
    val usernames = _usernames.asStateFlow()

    fun loadConversations(userId: String) {
        viewModelScope.launch {
            try {
                messageRepository.getConversations(userId)
                    .collect { convos ->
                        _conversations.value = convos
                        // Load usernames for all participants
                        val participantIds = convos.flatMap { it.participants }.distinct()
                        val usernameMap = mutableMapOf<String, String>()
                        participantIds.forEach { participantId ->
                            userRepository.getUser(participantId)?.let { user ->
                                usernameMap[participantId] = user.username
                            }
                        }
                        _usernames.value = usernameMap
                    }
            } catch (e: Exception) {
                Log.e("MessagesViewModel", "Error loading conversations", e)
            }
        }
    }

    fun selectConversation(conversation: Conversation?) {
        _selectedConversation.value = conversation
        if (conversation != null) {
            viewModelScope.launch {
                messageRepository.getMessages(conversation.id)
                    .collect { messages ->
                        _messages.value = messages
                    }
            }
        } else {
            _messages.value = emptyList()
        }
    }

    fun sendMessage(conversationId: String, senderId: String, content: String) {
        if (content.isBlank()) return
        
        viewModelScope.launch {
            val message = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = senderId,
                content = content.trim()
            )
            messageRepository.sendMessage(message)
        }
    }

    fun createConversation(participants: List<String>, isApproved: Boolean = false): String? {
        var conversationId: String? = null
        viewModelScope.launch {
            val conversation = Conversation(
                id = "",  // Will be set by repository
                participants = participants,
                lastMessage = null,
                lastMessageTimestamp = null,
                lastMessageSenderId = null,
                isApproved = isApproved
            )
            conversationId = messageRepository.createConversation(conversation)
        }
        return conversationId
    }

    fun approveConversation(conversationId: String) {
        viewModelScope.launch {
            messageRepository.approveConversation(conversationId)
        }
    }

    fun markMessagesAsRead(conversationId: String, userId: String) {
        viewModelScope.launch {
            messageRepository.markMessagesAsRead(conversationId, userId)
        }
    }
} 