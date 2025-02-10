package com.example.where.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.*
import com.example.where.data.model.Comment
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.OutlinedTextFieldDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentSheet(
    comments: List<Comment>,
    onDismiss: () -> Unit,
    onAddComment: (String, String?) -> Unit,
    onDeleteComment: (String) -> Unit,
    onLikeComment: (String) -> Unit,
    onLoadReplies: (String) -> Unit,
    currentUserId: String?,
    isLoading: Boolean = false,
    isVisible: Boolean,
    commentLikes: Map<String, Boolean> = emptyMap(),
    commentReplies: Map<String, List<Comment>> = emptyMap()
) {
    if (isVisible) {
        var commentText by remember { mutableStateOf("") }
        var isSending by remember { mutableStateOf(false) }
        var replyingTo by remember { mutableStateOf<Comment?>(null) }

        LaunchedEffect(isSending) {
            if (isSending) {
                try {
                    onAddComment(commentText, replyingTo?.id)
                    commentText = ""
                    replyingTo = null
                } finally {
                    isSending = false
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            windowInsets = WindowInsets(0)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .imePadding()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (replyingTo != null) "Reply to ${replyingTo?.authorUsername}" else "Comments",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (replyingTo != null) {
                        IconButton(onClick = { replyingTo = null }) {
                            Icon(Icons.Default.Close, "Cancel Reply")
                        }
                    }
                }

                // Comment Input
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = commentText,
                            onValueChange = { commentText = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                                .onKeyEvent { event ->
                                    if (event.key == Key.Enter && 
                                        event.type == KeyEventType.KeyUp && 
                                        commentText.isNotBlank() && 
                                        !isSending) {
                                        isSending = true
                                        true
                                    } else {
                                        false
                                    }
                                },
                            placeholder = { 
                                Text(if (replyingTo != null) "Write a reply..." else "Add a comment...") 
                            },
                            maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            ),
                            singleLine = true
                        )

                        FilledIconButton(
                            onClick = { if (commentText.isNotBlank()) isSending = true },
                            enabled = commentText.isNotBlank() && !isSending
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send"
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Comments List with loading indicator
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        reverseLayout = true
                    ) {
                        items(
                            items = comments,
                            key = { it.id }
                        ) { comment ->
                            CommentItem(
                                comment = comment,
                                currentUserId = currentUserId,
                                onDelete = { onDeleteComment(comment.id) },
                                onLike = { onLikeComment(comment.id) },
                                onReply = { replyingTo = comment },
                                onLoadReplies = { onLoadReplies(comment.id) },
                                isLiked = commentLikes[comment.id] ?: false,
                                replies = commentReplies[comment.id] ?: emptyList()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentItem(
    comment: Comment,
    currentUserId: String?,
    onDelete: () -> Unit,
    onLike: () -> Unit,
    onReply: () -> Unit,
    onLoadReplies: () -> Unit,
    isLiked: Boolean,
    replies: List<Comment>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = comment.authorUsername,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatTimestamp(comment.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = comment.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                
                // Action buttons
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Like button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { onLike() }
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isLiked) "Unlike" else "Like",
                            modifier = Modifier.size(16.dp),
                            tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (comment.likes > 0) {
                            Text(
                                text = comment.likes.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    // Reply button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { onReply() }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = "Reply"
                        )
                        Text(
                            text = "Reply",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Delete button for user's own comments
            if (currentUserId == comment.authorId) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete comment",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        
        // Show replies or "View replies" button
        if (comment.replyCount > 0) {
            if (replies.isEmpty()) {
                TextButton(
                    onClick = onLoadReplies,
                    modifier = Modifier.padding(start = 16.dp)
                ) {
                    Text("View ${comment.replyCount} ${if (comment.replyCount == 1) "reply" else "replies"}")
                }
            } else {
                Column(
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    replies.forEach { reply ->
                        CommentItem(
                            comment = reply,
                            currentUserId = currentUserId,
                            onDelete = onDelete,
                            onLike = { onLike() },
                            onReply = { onReply() },
                            onLoadReplies = { /* Nested replies not supported */ },
                            isLiked = false,
                            replies = emptyList()
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now" // less than 1 minute
        diff < 3600_000 -> "${diff / 60_000}m" // less than 1 hour
        diff < 86400_000 -> "${diff / 3600_000}h" // less than 1 day
        diff < 604800_000 -> "${diff / 86400_000}d" // less than 1 week
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
} 