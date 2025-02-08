package com.example.where.ui.messages

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.where.data.model.Conversation
import com.example.where.data.model.Message
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    currentUserId: String = FirebaseAuth.getInstance().currentUser?.uid ?: "",
    onNavigateBack: (Boolean) -> Unit,
    onNavigateToProfile: (String) -> Unit,
    shouldCloseConversation: Boolean = false,
    onConversationClosed: () -> Unit = {},
    viewModel: MessagesViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    val selectedConversation by viewModel.selectedConversation.collectAsState()
    val messages by viewModel.messages.collectAsState()

    // Get current user ID from Firebase Auth
    val userId = remember { FirebaseAuth.getInstance().currentUser?.uid }

    // Track if this is the initial composition
    var isInitialComposition by remember { mutableStateOf(true) }
    
    // Track if conversation is being closed internally
    var isClosingInternally by remember { mutableStateOf(false) }

    // Handle conversation close signal
    LaunchedEffect(shouldCloseConversation) {
        if (shouldCloseConversation && selectedConversation != null) {
            android.util.Log.d("MessagesScreen", "Received close conversation signal, closing conversation")
            isClosingInternally = true
            viewModel.selectConversation(null)
            onConversationClosed()
        }
    }

    // Update parent about conversation state, but don't trigger navigation on initial load or internal close
    LaunchedEffect(selectedConversation) {
        android.util.Log.d("MessagesScreen", "Conversation state changed: ${selectedConversation != null}, isInitial: $isInitialComposition, isClosingInternally: $isClosingInternally")
        if (!isInitialComposition && !isClosingInternally) {
            onNavigateBack(selectedConversation != null)
        } else {
            isInitialComposition = false
            isClosingInternally = false
        }
    }

    LaunchedEffect(userId) {
        if (userId != null) {
            Log.d("MessagesScreen", "Loading conversations for user: $userId")
            viewModel.loadConversations(userId)
        }
    }

    // Handle back press
    BackHandler(enabled = selectedConversation != null) {
        android.util.Log.d("MessagesScreen", "BackHandler triggered with selectedConversation: ${selectedConversation != null}")
        isClosingInternally = true
        viewModel.selectConversation(null)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (selectedConversation == null) {
            android.util.Log.d("MessagesScreen", "Rendering ConversationsList")
            ConversationsList(
                conversations = conversations,
                currentUserId = userId ?: "",
                onConversationClick = { conversation ->
                    viewModel.selectConversation(conversation)
                }
            )
        } else {
            selectedConversation?.let { conversation ->
                android.util.Log.d("MessagesScreen", "Rendering ChatScreen for conversation: ${conversation.id}")
                ChatScreen(
                    conversation = conversation,
                    messages = messages,
                    currentUserId = userId ?: "",
                    onSendMessage = { content ->
                        viewModel.sendMessage(conversation.id, userId ?: "", content)
                    },
                    onNavigateToProfile = onNavigateToProfile,
                    viewModel = viewModel
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationsList(
    conversations: List<Conversation>,
    currentUserId: String,
    onConversationClick: (Conversation) -> Unit
) {
    if (conversations.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "No conversations yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Start a conversation from someone's profile",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(conversations) { conversation ->
                ConversationItem(
                    conversation = conversation,
                    currentUserId = currentUserId,
                    onClick = { onConversationClick(conversation) }
                )
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    currentUserId: String,
    onClick: () -> Unit,
    viewModel: MessagesViewModel = hiltViewModel()
) {
    val otherParticipantId = conversation.participants.first { it != currentUserId }
    val usernames by viewModel.usernames.collectAsState()
    val otherParticipantName = usernames[otherParticipantId] ?: "Unknown User"
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = otherParticipantName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = otherParticipantName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (conversation.lastMessage != null) {
                    Text(
                        text = conversation.lastMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!conversation.isApproved) {
                Text(
                    text = "Pending",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    conversation: Conversation,
    messages: List<Message>,
    currentUserId: String,
    onSendMessage: (String) -> Unit,
    onNavigateToProfile: (String) -> Unit,
    viewModel: MessagesViewModel = hiltViewModel()
) {
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val usernames by viewModel.usernames.collectAsState()
    val otherParticipantId = conversation.participants.first { it != currentUserId }
    val otherParticipantName = usernames[otherParticipantId] ?: "Unknown User"

    // Mark messages as read when viewed
    LaunchedEffect(Unit) {
        Log.d("ChatScreen", "Chat opened, marking messages as read for conversation: ${conversation.id}")
        viewModel.markMessagesAsRead(conversation.id, currentUserId)
    }

    // Also mark messages as read when new messages arrive
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            val unreadMessages = messages.any { !it.read && it.senderId != currentUserId }
            if (unreadMessages) {
                Log.d("ChatScreen", "New unread messages detected, marking as read")
                viewModel.markMessagesAsRead(conversation.id, currentUserId)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // User header
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToProfile(otherParticipantId) },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = otherParticipantName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Text(
                    text = otherParticipantName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                val isCurrentUser = message.senderId == currentUserId
                MessageBubble(
                    message = message,
                    isCurrentUser = isCurrentUser,
                    dateFormat = dateFormat
                )
            }
        }

        // Message input
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message") },
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (messageText.isNotBlank()) {
                                onSendMessage(messageText)
                                messageText = ""
                            }
                        }
                    )
                )
                
                IconButton(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            onSendMessage(messageText)
                            messageText = ""
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send message",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    isCurrentUser: Boolean,
    dateFormat: SimpleDateFormat
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isCurrentUser) 16.dp else 4.dp,
                bottomEnd = if (isCurrentUser) 4.dp else 16.dp
            ),
            color = if (isCurrentUser) 
                MaterialTheme.colorScheme.primary 
            else 
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = message.content,
                    color = if (isCurrentUser) 
                        MaterialTheme.colorScheme.onPrimary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
                
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateFormat.format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrentUser) 
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    
                    if (isCurrentUser) {
                        Icon(
                            imageVector = if (message.readBy != null) Icons.Default.DoneAll else Icons.Default.Done,
                            contentDescription = if (message.readBy != null) "Read" else "Sent",
                            modifier = Modifier.size(14.dp),
                            tint = if (message.readBy != null)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
} 