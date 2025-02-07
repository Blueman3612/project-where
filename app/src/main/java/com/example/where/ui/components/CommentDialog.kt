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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentSheet(
    comments: List<Comment>,
    onDismiss: () -> Unit,
    onAddComment: (String) -> Unit,
    onDeleteComment: (String) -> Unit,
    currentUserId: String?,
    isLoading: Boolean = false,
    isVisible: Boolean
) {
    if (isVisible) {
        var commentText by remember { mutableStateOf("") }
        var isSending by remember { mutableStateOf(false) }

        LaunchedEffect(isSending) {
            if (isSending) {
                try {
                    onAddComment(commentText)
                    commentText = ""
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
                        text = "Comments",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${comments.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                            placeholder = { Text("Add a comment...") },
                            maxLines = 3,
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
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
                                    imageVector = Icons.Default.Send,
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
                                onDelete = { onDeleteComment(comment.id) }
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
    onDelete: () -> Unit
) {
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