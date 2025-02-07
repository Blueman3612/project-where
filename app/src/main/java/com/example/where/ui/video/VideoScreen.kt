package com.example.where.ui.video

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(16.dp)
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
                onAddComment = { viewModel.addComment(it) },
                onDeleteComment = { viewModel.deleteComment(it) },
                currentUserId = viewModel.currentUserId,
                isLoading = viewModel.isLoadingComments,
                isVisible = viewModel.showComments
            )
        }
    }
} 