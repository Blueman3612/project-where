package com.example.where.ui.video

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import java.lang.ref.WeakReference
import com.example.where.ui.components.CommentSheet
import com.example.where.ui.components.LikeAnimation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.media3.common.util.UnstableApi

@androidx.media3.common.util.UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(
    videoId: String,
    onNavigateBack: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    viewModel: VideoViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val currentPlayer = remember { 
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE // Enable looping
        }
    }
    val currentPlayerView = remember { mutableStateOf<WeakReference<PlayerView>?>(null) }
    
    // Load video data
    LaunchedEffect(videoId) {
        viewModel.loadVideo(videoId)
    }
    
    // Clean up player when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            currentPlayer.release()
        }
    }
    
    // Handle video loading
    LaunchedEffect(viewModel.video) {
        viewModel.video?.let { video ->
            currentPlayerView.value?.get()?.player = null
            
            currentPlayer.apply {
                stop()
                clearMediaItems()
                setMediaItem(MediaItem.fromUri(video.url))
                prepare()
                playWhenReady = true
            }
            
            currentPlayerView.value?.get()?.apply {
                hideController()
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(AndroidColor.BLACK)
                player = currentPlayer
                player?.play()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Video Player with Overlay
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f/16f)
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { context ->
                            PlayerView(context).also {
                                it.player = currentPlayer
                                it.useController = false // Hide default controls
                                it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // Fill the frame
                                currentPlayerView.value = WeakReference(it)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = { offset ->
                                        viewModel.showLikeAnimation()
                                        viewModel.toggleLike()
                                    }
                                )
                            }
                    )

                    // Like Animation overlay
                    if (viewModel.showLikeAnimation) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            LikeAnimation(
                                visible = true,
                                onAnimationEnd = { viewModel.hideLikeAnimation() }
                            )
                        }
                    }

                    // Author info overlay at top
                    viewModel.video?.let { video ->
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(16.dp)
                                .clickable { onNavigateToProfile(video.authorId) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Author info (left side)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.2f))
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        "Profile",
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .align(Alignment.Center)
                                    )
                                }
                                Text(
                                    text = video.authorUsername,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                            }
                            
                            // Timestamp (right side)
                            Text(
                                text = viewModel.formatTimestamp(video.createdAt),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Overlay for likes and comments
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Like Button and Count
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Like Count
                            Text(
                                text = viewModel.formatNumber(viewModel.currentLikeCount),
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            // Like Button
                            IconButton(
                                onClick = { viewModel.toggleLike() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (viewModel.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (viewModel.isLiked) "Unlike" else "Like",
                                    tint = if (viewModel.isLiked) Color.Red else Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        // Comment Button and Count
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Comment Count
                            viewModel.video?.let { video ->
                                Text(
                                    text = viewModel.formatNumber(video.comments),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            // Comment Button
                            IconButton(
                                onClick = { viewModel.toggleComments() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = "Comments",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
                
                // Map showing video location
                viewModel.video?.let { video ->
                    var mapHeight by remember { mutableStateOf(200.dp) }
                    var isDragging by remember { mutableStateOf(false) }
                    var isPanelVisible by remember { mutableStateOf(true) }
                    val density = LocalDensity.current
                    
                    if (isPanelVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(mapHeight)
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp)
                        ) {
                            // Resizable Map Content
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                            ) {
                                val cameraPositionState = rememberCameraPositionState {
                                    position = CameraPosition.fromLatLngZoom(video.location, 8f)
                                }
                                
                                GoogleMap(
                                    modifier = Modifier.fillMaxSize(),
                                    cameraPositionState = cameraPositionState,
                                    properties = MapProperties(
                                        isMyLocationEnabled = false,
                                        mapType = MapType.NORMAL
                                    )
                                ) {
                                    Marker(
                                        state = MarkerState(position = video.location),
                                        title = "Video Location"
                                    )
                                }
                                
                                // Drag Handle
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                        .height(24.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surface,
                                            RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                                        )
                                        .pointerInput(Unit) {
                                            detectVerticalDragGestures(
                                                onDragStart = { isDragging = true },
                                                onDragEnd = {
                                                    isDragging = false
                                                    // Hide panel if dragged below minimum height
                                                    if (mapHeight < 100.dp) {
                                                        isPanelVisible = false
                                                        mapHeight = 200.dp // Reset for next show
                                                    }
                                                },
                                                onDragCancel = { isDragging = false },
                                                onVerticalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    val dragDp = (dragAmount / density.density).dp
                                                    mapHeight = (mapHeight - dragDp)
                                                        .coerceIn(50.dp, 400.dp)
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Drag indicator line
                                    Box(
                                        modifier = Modifier
                                            .width(32.dp)
                                            .height(4.dp)
                                            .background(
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                                RoundedCornerShape(2.dp)
                                            )
                                    )
                                }
                            }
                        }
                    }
                    
                    // Show button to restore map when hidden
                    if (!isPanelVisible) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            FloatingActionButton(
                                onClick = { isPanelVisible = true },
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Map,
                                    contentDescription = "Show Map"
                                )
                            }
                        }
                    }
                }
            }
            
            // Loading indicator
            if (viewModel.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            
            // Error message
            viewModel.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Text(error)
                }
            }

            // Comment Sheet
            CommentSheet(
                comments = viewModel.comments.collectAsStateWithLifecycle().value,
                onDismiss = { viewModel.toggleComments() },
                onAddComment = { text, parentId -> viewModel.addComment(text, parentId) },
                onDeleteComment = { viewModel.deleteComment(it) },
                onLikeComment = { viewModel.toggleCommentLike(it) },
                onLoadReplies = { viewModel.loadReplies(it) },
                currentUserId = viewModel.currentUserId,
                isLoading = viewModel.isLoadingComments,
                isVisible = viewModel.showComments,
                commentLikes = viewModel.commentLikes.collectAsStateWithLifecycle().value,
                commentReplies = viewModel.commentReplies.collectAsStateWithLifecycle().value
            )
        }
    }
} 