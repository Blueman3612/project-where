package com.example.where.ui

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.PlaybackException
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import android.graphics.Color as AndroidColor
import android.util.Log
import java.lang.ref.WeakReference
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.offset
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlin.math.roundToInt
import com.example.where.data.model.Comment
import com.example.where.data.model.Video
import com.example.where.ui.components.CommentSheet
import com.example.where.ui.components.LikeAnimation
import com.example.where.ui.components.TopBar
import kotlinx.coroutines.flow.StateFlow
import android.os.Build
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import android.graphics.drawable.Drawable
import androidx.compose.ui.zIndex
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.example.where.util.LanguageDetector
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import com.example.where.util.LanguageDetector.LanguageResult
import androidx.compose.foundation.BorderStroke

private const val TAG = "MainScreen"

private data class PlayerError(
    val message: String,
    val cause: Throwable?
)

// Memory tracking helper
private object MemoryTracker {
    private var lastUsedMemory = 0L
    private const val MB = 1024 * 1024

    fun logMemoryUsage(tag: String) {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val delta = usedMemory - lastUsedMemory
        
        val deltaPrefix = if (delta >= 0) "+" else ""
        
        Log.d(TAG, "Memory Usage [$tag] - Used: ${usedMemory}MB (Δ: $deltaPrefix${delta}MB)")
        Log.d(TAG, "Memory Details [$tag]:")
        Log.d(TAG, "- Total: ${runtime.totalMemory() / MB}MB")
        Log.d(TAG, "- Free: ${runtime.freeMemory() / MB}MB")
        Log.d(TAG, "- Max: ${runtime.maxMemory() / MB}MB")
        
        lastUsedMemory = usedMemory
    }
}

// Add this helper function at the top level of the file
private fun formatNumber(number: Int): String {
    return when {
        number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000.0)
        number >= 1_000 -> String.format("%.1fK", number / 1_000.0)
        else -> number.toString()
    }
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onProfileClick: () -> Unit = {},
    onNavigateToProfile: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val currentPlayerRef = remember { mutableStateOf<ExoPlayer?>(null) }
    val preloadPlayerRef = remember { mutableStateOf<ExoPlayer?>(null) }
    val currentPlayerView = remember { mutableStateOf<WeakReference<PlayerView>?>(null) }
    
    // Function to create optimized player
    fun createOptimizedPlayer() = ExoPlayer.Builder(context)
        .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS / 2,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS / 2,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS / 2,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 2
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setUserAgent("Mozilla/5.0")
                        .setConnectTimeoutMs(8000)
                        .setReadTimeoutMs(8000)
                        .setAllowCrossProtocolRedirects(true)
                )
        )
        .build().apply {
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = false
            
            // Disable audio completely
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
                
            // Add error handling
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("MainScreen", "Player error: ${error.message}")
                    // Attempt to recover from the error
                    if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
                        prepare()
                    }
                }
            })
        }

    val currentPlayer = remember { 
        createOptimizedPlayer().also {
            currentPlayerRef.value?.release()
            currentPlayerRef.value = it
        }
    }

    val preloadPlayer = remember {
        createOptimizedPlayer().also {
            preloadPlayerRef.value?.release()
            preloadPlayerRef.value = it
        }
    }

    // Handle cleanup
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    currentPlayer.pause()
                    preloadPlayer.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (viewModel.currentVideo != null) {
                        currentPlayer.play()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    currentPlayer.stop()
                    preloadPlayer.stop()
                    currentPlayer.clearMediaItems()
                    preloadPlayer.clearMediaItems()
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentPlayerView.value?.get()?.player = null
            currentPlayerView.value = null
            
            currentPlayer.stop()
            preloadPlayer.stop()
            currentPlayer.clearMediaItems()
            preloadPlayer.clearMediaItems()
            currentPlayer.release()
            preloadPlayer.release()
            currentPlayerRef.value = null
            preloadPlayerRef.value = null
            
            System.gc()
        }
    }
    
    // Get comments state from viewModel with explicit types
    val comments: List<Comment> by viewModel.comments.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val showComments: Boolean by viewModel.showComments.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val isLoadingComments: Boolean by viewModel.isLoadingComments.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val currentUserId = remember { Firebase.auth.currentUser?.uid }
    val commentLikes: Map<String, Boolean> by viewModel.commentLikes.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val commentReplies: Map<String, List<Comment>> by viewModel.commentReplies.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)

    // Update state collection with proper lifecycle owner and explicit types
    val lastGuessScore: Int? by viewModel.lastGuessScore.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val lastGuessDistance: Double? by viewModel.lastGuessDistance.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val currentVideoUrl: Video? by viewModel.currentVideoUrl.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)

    // Update state collection for video URL with explicit types
    val currentVideo: Video? by viewModel.currentVideo.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val isLoading: Boolean by viewModel.isLoading.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val error: String? by viewModel.error.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val showLikeAnimation: Boolean by viewModel.showLikeAnimation.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val currentLikeCount: Int by viewModel.currentLikeCount.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val isLiked: Boolean by viewModel.isLiked.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val nextVideo: Video? by viewModel.nextVideo.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val detectedLanguage: LanguageDetector.LanguageResult? by viewModel.detectedLanguage.collectAsStateWithLifecycle<LanguageDetector.LanguageResult?>(
        lifecycleOwner = lifecycleOwner
    )

    // Add this for commentCount
    val commentCount = remember(currentVideo) { currentVideo?.comments ?: 0 }

    // Update camera position state with remember
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            currentVideo?.location ?: LatLng(0.0, 0.0),
            2f
        )
    }

    // Add these state declarations with rememberSaveable
    var showOverlay by rememberSaveable { mutableStateOf(true) }
    var selectedLocation by rememberSaveable { mutableStateOf<LatLng?>(null) }
    var showMap by rememberSaveable { mutableStateOf(false) }
    var hasGuessedCurrentVideo by rememberSaveable { mutableStateOf(false) }
    var swipeOffset by remember { mutableStateOf(0f) }
    var isSwipeInProgress by remember { mutableStateOf(false) }
    var lastDragDirection by remember { mutableStateOf(0f) }
    var mapPanelHeightPercent by remember { mutableStateOf(0.6f) }
    var isMapLoaded by remember { mutableStateOf(false) }
    var showScoreOverlay by rememberSaveable { mutableStateOf(false) }

    // Animation for overlay controls
    val offsetY by animateFloatAsState(
        targetValue = if (showOverlay) 0f else -400f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "Overlay Animation"
    )

    // Animation for map panel height
    val animatedMapHeight by animateFloatAsState(
        targetValue = if (showMap) mapPanelHeightPercent else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "Map Panel Animation"
    )

    val scoreOverlayAlpha by animateFloatAsState(
        targetValue = if (showScoreOverlay) 0.95f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "Score Overlay Animation"
    )
    val scoreScale by animateFloatAsState(
        targetValue = if (showScoreOverlay) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "Score Scale Animation"
    )

    // Update video URL handling
    LaunchedEffect(currentVideo) {
        currentVideo?.let { video ->
            // Reset guessing state for new video
            selectedLocation = null
            hasGuessedCurrentVideo = false
            
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

    // Update next video handling
    LaunchedEffect(nextVideo) {
        nextVideo?.let { video ->
            preloadPlayer.apply {
                setMediaItem(MediaItem.fromUri(video.url))
                prepare()
                playWhenReady = false
            }
        }
    }

    // Update the LaunchedEffect for map preloading
    LaunchedEffect(Unit) {
        // Start with a wider view of the world
        cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 1f)
    }

    // Effect to handle screen disposal and returns
    DisposableEffect(Unit) {
        onDispose {
            if (hasGuessedCurrentVideo) {
                viewModel.switchToNextVideo()
                selectedLocation = null
                hasGuessedCurrentVideo = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Video Player Section (now full screen)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(swipeOffset.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (showMap) {
                                showMap = false
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { 
                            isSwipeInProgress = true
                            currentPlayer.pause()
                            currentPlayerView.value?.get()?.player = null
                        },
                        onDragEnd = {
                            if (swipeOffset < -200) {  // Reduced threshold for easier swiping
                                viewModel.switchToNextVideo()
                                selectedLocation = null
                                hasGuessedCurrentVideo = false  // Reset guessing state
                                showMap = false
                            }
                            swipeOffset = 0f
                            isSwipeInProgress = false
                            if (!showMap) {
                                currentPlayerView.value?.get()?.player = currentPlayer
                                currentPlayer.play()
                            }
                        },
                        onDragCancel = {
                            swipeOffset = 0f
                            isSwipeInProgress = false
                            if (!showMap) {
                                currentPlayerView.value?.get()?.player = currentPlayer
                                currentPlayer.play()
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            swipeOffset = (swipeOffset + dragAmount).coerceAtMost(0f)
                        }
                    )
                }
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color.White
                    )
                }
            } else {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                            useController = false
                            setKeepContentOnPlayerReset(true)
                            setShutterBackgroundColor(AndroidColor.BLACK)
                            
                            // Set resize mode for proper letterboxing
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            
                            // Optimize video rendering
                            setKeepScreenOn(true)
                            
                            // Enable hardware acceleration for better performance
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                            }
                            
                            player = currentPlayer
                            currentPlayerView.value = WeakReference(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Like Animation overlay
            if (showLikeAnimation) {
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

            // Overlay controls
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .offset(y = offsetY.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Top Row with Profile and Likes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        // Left side: Profile and Username
                        Column(
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Profile and Username Row
                            Row(
                                modifier = Modifier
                                    .clickable { 
                                        currentVideo?.let { video ->
                                            onNavigateToProfile(video.authorId)
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                // Profile Picture
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f))
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

                                // Username
                                Text(
                                    text = currentVideo?.authorUsername ?: "Unknown",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .background(
                                            color = Color.Black.copy(alpha = 0.5f),
                                            shape = MaterialTheme.shapes.medium
                                        )
                                        .padding(vertical = 4.dp, horizontal = 8.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Language Hint Button
                            currentVideo?.let { video ->
                                video.primaryLanguage?.let { language ->
                                    val languageHintRevealed by viewModel.languageHintRevealed.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
                                    val expandedWidth by animateFloatAsState(
                                        targetValue = if (languageHintRevealed) 200f else 40f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessLow
                                        ),
                                        label = "Width Animation"
                                    )
                                    
                                    Surface(
                                        onClick = { if (!languageHintRevealed) viewModel.revealLanguageHint() },
                                        enabled = !languageHintRevealed,
                                        modifier = Modifier
                                            .width(expandedWidth.dp)
                                            .height(40.dp)
                                            .clip(CircleShape)
                                            .background(
                                                color = if (!languageHintRevealed) {
                                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                                                } else {
                                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                                                }
                                            ),
                                        color = Color.Transparent,
                                        shape = CircleShape
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = if (languageHintRevealed) 
                                                Arrangement.Start else Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Translate,
                                                contentDescription = "Language Hint",
                                                tint = if (!languageHintRevealed) 
                                                    MaterialTheme.colorScheme.onTertiary
                                                else 
                                                    MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            
                                            if (languageHintRevealed) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = getLanguageDisplayName(language),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                                    )
                                                    video.languageConfidence?.let { confidence ->
                                                        Text(
                                                            text = "Confidence: ${(confidence * 100).toInt()}%",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Right side: Likes and Comments
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Likes Row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                // Like Count
                                Text(
                                    text = formatNumber(currentLikeCount),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .background(
                                            color = Color.Black.copy(alpha = 0.5f),
                                            shape = MaterialTheme.shapes.medium
                                        )
                                        .padding(vertical = 4.dp, horizontal = 8.dp)
                                )
                                // Like Button
                                IconButton(
                                    onClick = { viewModel.toggleLike() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(
                                            color = Color.Black.copy(alpha = 0.5f),
                                            shape = CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = if (isLiked) "Unlike" else "Like",
                                        tint = if (isLiked) Color.Red else Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            
                            // Comments Row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                // Comments Count
                                Text(
                                    text = formatNumber(commentCount),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .background(
                                            color = Color.Black.copy(alpha = 0.5f),
                                            shape = MaterialTheme.shapes.medium
                                        )
                                        .padding(vertical = 4.dp, horizontal = 8.dp)
                                )
                                // Comment Button
                                IconButton(
                                    onClick = { viewModel.toggleComments() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(
                                            color = Color.Black.copy(alpha = 0.5f),
                                            shape = CircleShape
                                        )
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
                }
            }
        }

        // Map Pull Handle (only show when map is hidden)
        if (!showMap) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(bottom = 8.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { /* start drag */ },
                            onDragEnd = {
                                if (mapPanelHeightPercent > 0.1f) {
                                    showMap = true
                                }
                                mapPanelHeightPercent = 0.6f // Reset to default height
                            },
                            onDragCancel = {
                                mapPanelHeightPercent = 0.6f // Reset to default height
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                // Convert drag to height percentage (negative dragAmount means upward)
                                mapPanelHeightPercent = (-dragAmount / 1000f)
                                    .coerceIn(0f, 0.6f)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // Pull up indicator
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(
                            Color.White.copy(alpha = 0.5f),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }

        // Comment Dialog
        CommentSheet(
            comments = comments,
            onDismiss = { viewModel.toggleComments() },
            onAddComment = { text, parentId -> viewModel.addComment(text, parentId) },
            onDeleteComment = { commentId -> viewModel.deleteComment(commentId) },
            onLikeComment = { commentId -> viewModel.toggleCommentLike(commentId) },
            onLoadReplies = { commentId -> viewModel.loadReplies(commentId) },
            currentUserId = currentUserId,
            isLoading = isLoadingComments,
            isVisible = showComments,
            commentLikes = commentLikes,
            commentReplies = commentReplies
        )

        // Move the score overlay to be the very last element in the Box
        if (hasGuessedCurrentVideo && showScoreOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scoreOverlayAlpha * 0.7f))
                    .clickable { showScoreOverlay = false }
                    .zIndex(100f),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .padding(32.dp)
                        .graphicsLayer {
                            scaleX = scoreScale
                            scaleY = scoreScale
                            alpha = scoreOverlayAlpha
                        },
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .width(IntrinsicSize.Min),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Score Section
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "${lastGuessScore}",
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            // Show hint penalty if used
                            val hintRevealed by viewModel.languageHintRevealed.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
                            if (hintRevealed) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Translate,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "-1000",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        Divider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )

                        // Distance with icon
                        lastGuessDistance?.let { distance ->
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = viewModel.formatDistance(distance),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Next Video Button
                        FilledTonalButton(
                            onClick = { 
                                showScoreOverlay = false
                                showMap = false
                                viewModel.switchToNextVideo()
                                selectedLocation = null
                                hasGuessedCurrentVideo = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("NEXT VIDEO")
                        }
                    }
                }
            }
        }

        // Map Panel Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(animatedMapHeight)
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    alpha = if (showMap) 1f else 0f
                }
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Drag Handle at the top
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                            )
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragStart = { /* start drag */ },
                                    onDragEnd = {
                                        // If dragged down significantly or below minimum height, hide map
                                        if (mapPanelHeightPercent < 0.3f) {
                                            showMap = false
                                            // Reset height for next time
                                            mapPanelHeightPercent = 0.6f
                                        }
                                    },
                                    onDragCancel = { /* cancel drag */ },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        // Make downward dragging more responsive for dismissal
                                        val scaleFactor = if (dragAmount > 0) 2000f else 1000f
                                        mapPanelHeightPercent = (mapPanelHeightPercent - (dragAmount / scaleFactor))
                                            .coerceIn(0.2f, 0.8f)
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

                    // Map Content
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 24.dp) // Account for drag handle
                            // Only disable pointer events when map is hidden
                            .then(if (!showMap) Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                }
                            } else Modifier)
                    ) {
                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraPositionState,
                            properties = MapProperties(
                                // Use NONE map type when hidden for better performance
                                mapType = if (showMap) MapType.NORMAL else MapType.NONE,
                                isMyLocationEnabled = false,
                                mapStyleOptions = null,
                                isBuildingEnabled = false, // Disable 3D buildings for better performance
                                isIndoorEnabled = false,   // Disable indoor maps for better performance
                                isTrafficEnabled = false   // Disable traffic data for better performance
                            ),
                            uiSettings = MapUiSettings(
                                zoomControlsEnabled = true,  // Re-enable zoom controls
                                zoomGesturesEnabled = showMap,
                                scrollGesturesEnabled = showMap,
                                rotationGesturesEnabled = false,
                                tiltGesturesEnabled = false,
                                compassEnabled = false,
                                mapToolbarEnabled = false
                            ),
                            onMapLoaded = {
                                isMapLoaded = true
                            },
                            onMapClick = { latLng ->
                                if (!hasGuessedCurrentVideo && showMap) {
                                    selectedLocation = latLng
                                }
                            }
                        ) {
                            // Show selected location marker
                            selectedLocation?.let { location ->
                                Marker(
                                    state = MarkerState(position = location),
                                    title = "Your Guess",
                                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                                )
                            }

                            // Show actual location and polyline when guess is submitted
                            if (hasGuessedCurrentVideo && selectedLocation != null) {
                                currentVideo?.let { video ->
                                    // Actual location marker
                                    Marker(
                                        state = MarkerState(position = video.location),
                                        title = "Actual Location",
                                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                                    )

                                    // Dotted line between guess and actual location
                                    Polyline(
                                        points = listOf(selectedLocation!!, video.location),
                                        pattern = listOf(Dot(), Gap(20f)),
                                        color = androidx.compose.ui.graphics.Color.DarkGray,
                                        width = 5f
                                    )

                                    // Update camera position to show both markers
                                    LaunchedEffect(selectedLocation, video.location) {
                                        val (bounds, zoom) = calculateBounds(selectedLocation!!, video.location)
                                        cameraPositionState.animate(
                                            update = CameraUpdateFactory.newLatLngBounds(bounds, 100),
                                            durationMs = 1000
                                        )
                                    }
                                }
                            }
                        }

                        // Submit Guess Button
                        if (!hasGuessedCurrentVideo && selectedLocation != null && showMap) {
                            Button(
                                onClick = { 
                                    selectedLocation?.let { location ->
                                        viewModel.submitGuess(location)
                                        hasGuessedCurrentVideo = true
                                        showScoreOverlay = true
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp)
                            ) {
                                Text("Submit Guess")
                            }
                        }
                    }
                }
            }
        }

        // Error message
        error?.let { error ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Snackbar(
                    modifier = Modifier
                        .wrapContentSize()
                        .align(Alignment.BottomCenter)
                ) {
                    Text(error)
                }
            }
        }

        // Add loading indicator for map
        if (!isMapLoaded && showMap) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        // Video player loading state
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White
                )
            }
        }
    }
}

// Calculate map bounds
fun calculateBounds(point1: LatLng, point2: LatLng): Pair<LatLngBounds, Float> {
    val builder = LatLngBounds.builder()
    builder.include(point1)
    builder.include(point2)
    val bounds = builder.build()
    
    val width = bounds.northeast.longitude - bounds.southwest.longitude
    val height = bounds.northeast.latitude - bounds.southwest.latitude
    val zoom = when {
        width == 0.0 && height == 0.0 -> 15f
        width > height -> 
            (360.0 / (width * 2.5)).let { Math.log(it) / Math.log(2.0) }.toFloat()
        else -> 
            (180.0 / (height * 2.5)).let { Math.log(it) / Math.log(2.0) }.toFloat()
    }.coerceIn(2f, 15f)
    
    return bounds to zoom
}

// Helper function to convert language codes to display names
private fun getLanguageDisplayName(languageCode: String): String {
    return when (languageCode.lowercase()) {
        "ar" -> "Arabic"
        "zh" -> "Chinese"
        "en" -> "English"
        "fr" -> "French"
        "de" -> "German"
        "hi" -> "Hindi"
        "id" -> "Indonesian"
        "it" -> "Italian"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "ms" -> "Malay"
        "fa" -> "Persian"
        "pt" -> "Portuguese"
        "ru" -> "Russian"
        "es" -> "Spanish"
        "th" -> "Thai"
        "tr" -> "Turkish"
        "ur" -> "Urdu"
        "vi" -> "Vietnamese"
        else -> languageCode.uppercase()
    }
} 