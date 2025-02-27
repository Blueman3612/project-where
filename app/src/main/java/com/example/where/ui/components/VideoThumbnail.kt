package com.example.where.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.where.data.model.Video

@Composable
fun VideoThumbnail(
    video: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var thumbnailUrl by remember(video.id, video.thumbnailUrl) { mutableStateOf(video.thumbnailUrl) }
    var isLoading by remember { mutableStateOf(false) }
    val viewModel: ThumbnailViewModel = hiltViewModel()

    Box(
        modifier = modifier
            .aspectRatio(9f/16f)
            .padding(1.dp)
            .clickable { onClick() }
    ) {
        // Video Thumbnail
        if (thumbnailUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Show placeholder and loading indicator if generating thumbnail
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.VideoFile,
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Generate thumbnail if not exists
            LaunchedEffect(video.id) {
                if (thumbnailUrl == null && !isLoading) {
                    isLoading = true
                    try {
                        thumbnailUrl = viewModel.generateThumbnail(video)
                    } finally {
                        isLoading = false
                    }
                }
            }
        }

        // Relative timestamp overlay at top right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = formatRelativeTime(video.createdAt),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }

        // Likes count overlay at bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Color.White
                )
                Text(
                    text = formatCompactNumber(video.likes),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // Comments count overlay at bottom right
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Color.White
                )
                Text(
                    text = formatCompactNumber(video.comments),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private fun formatCompactNumber(number: Int): String {
    return when {
        number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000.0)
        number >= 1_000 -> String.format("%.1fK", number / 1_000.0)
        else -> number.toString()
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now" // less than 1 minute
        diff < 3600_000 -> "${diff / 60_000}m" // less than 1 hour
        diff < 86400_000 -> "${diff / 3600_000}h" // less than 1 day
        diff < 604800_000 -> "${diff / 86400_000}d" // less than 1 week
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
} 