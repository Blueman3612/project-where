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
    
    // Add showOverlay state
    var showOverlay by remember { mutableStateOf(true) }
    
    // Animation for overlay controls
    val offsetY by animateFloatAsState(
        targetValue = if (showOverlay) 0f else -400f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "Overlay Animation"
    )

    // Get comments state from viewModel
    val comments by viewModel.comments.collectAsStateWithLifecycle(
        initialValue = emptyList(),
        lifecycleOwner = lifecycleOwner
    )
    val showComments by viewModel.showComments.collectAsStateWithLifecycle(
        lifecycleOwner = lifecycleOwner
    )
    val isLoadingComments by viewModel.isLoadingComments.collectAsStateWithLifecycle(
        lifecycleOwner = lifecycleOwner
    )
    val currentUserId = remember { Firebase.auth.currentUser?.uid }
    val commentLikes by viewModel.commentLikes.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
        lifecycleOwner = lifecycleOwner
    )
    val commentReplies by viewModel.commentReplies.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
        lifecycleOwner = lifecycleOwner
    )

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

    // Handle current video changes
    LaunchedEffect(viewModel.currentVideoUrl) {
        viewModel.currentVideoUrl?.let { video ->
            currentPlayer.apply {
                stop()
                clearMediaItems()
            }
            
            currentPlayerView.value?.get()?.player = null
            
            currentPlayer.apply {
                setMediaItem(MediaItem.fromUri(video.url))
                prepare()
                playWhenReady = true
            }
            
            currentPlayerView.value?.get()?.apply {
                hideController()
                setKeepContentOnPlayerReset(true)
                
                // Set the default background color first
                setShutterBackgroundColor(AndroidColor.BLACK)
                
                // Set the thumbnail as the background if available
                viewModel.currentVideoUrl?.thumbnailUrl?.let { thumbnailUrl ->
                    // Create a drawable from the thumbnail URL using Glide
                    Glide.with(context)
                        .load(thumbnailUrl)
                        .into(object : CustomTarget<Drawable>() {
                            override fun onResourceReady(
                                resource: Drawable,
                                transition: Transition<in Drawable>?
                            ) {
                                defaultArtwork = resource
                                setDefaultArtwork(resource)
                            }
                            
                            override fun onLoadCleared(placeholder: Drawable?) {
                                // Do nothing
                            }
                        })
                }
                
                player = currentPlayer
                player?.play()
            }
        }
    }

    // Handle preloading of next video
    LaunchedEffect(viewModel.nextVideo) {
        viewModel.nextVideo?.let { video ->
            preloadPlayer.apply {
                setMediaItem(MediaItem.fromUri(video.url))
                prepare()
                playWhenReady = false
            }
        }
    }

    var selectedLocation by remember { mutableStateOf<LatLng?>(null) }
    var showMap by remember { mutableStateOf(false) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            viewModel.currentVideoUrl?.location ?: LatLng(0.0, 0.0),
            2f
        )
    }

    var swipeOffset by remember { mutableStateOf(0f) }
    var isSwipeInProgress by remember { mutableStateOf(false) }
    var hasGuessedCurrentVideo by remember { mutableStateOf(false) }
    var lastDragDirection by remember { mutableStateOf(0f) }
    
    // Animation for map panel
    val mapPanelHeightPercent by animateFloatAsState(
        targetValue = if (showMap) 0.6f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "Map Panel Animation"
    )

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
            if (viewModel.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
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
                        Row(
                            modifier = Modifier
                                .clickable { 
                                    viewModel.currentVideoUrl?.let { video ->
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
                                text = viewModel.currentVideoUrl?.authorUsername ?: "Unknown",
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
                                    text = viewModel.formatNumber(viewModel.currentLikeCount),
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
                                        imageVector = if (viewModel.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = if (viewModel.isLiked) "Unlike" else "Like",
                                        tint = if (viewModel.isLiked) Color.Red else Color.White,
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
                                        text = viewModel.formatNumber(viewModel.commentCount),
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

        // Score Display overlay at bottom of video (keep this outside the animated overlay)
        if (hasGuessedCurrentVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Total Score
                    Column(
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "Total Score",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "${viewModel.currentScore}",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    }

                    // Last Guess Score and Distance
                    viewModel.lastGuessScore?.let { score ->
                        Column(
                            horizontalAlignment = Alignment.End
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = Color.Green,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "$score",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.Green
                                )
                            }
                            viewModel.lastGuessDistance?.let { distance ->
                                Text(
                                    text = viewModel.formatDistance(distance),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Map Panel Overlay
        AnimatedVisibility(
            visible = showMap,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(mapPanelHeightPercent)
                .align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        onMapClick = { latLng ->
                            if (!hasGuessedCurrentVideo) {
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
                            viewModel.currentVideoUrl?.let { video ->
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
                    if (!hasGuessedCurrentVideo && selectedLocation != null) {
                        Button(
                            onClick = { 
                                selectedLocation?.let { location ->
                                    viewModel.submitGuess(location)
                                    hasGuessedCurrentVideo = true
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

        // Show/Hide Map Button
        FloatingActionButton(
            onClick = { showMap = !showMap },
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.BottomEnd)
        ) {
            Icon(
                imageVector = if (showMap) Icons.Default.Close else Icons.Default.Map,
                contentDescription = if (showMap) "Hide Map" else "Show Map"
            )
        }

        // Error message
        viewModel.error?.let { error ->
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
    }
} 