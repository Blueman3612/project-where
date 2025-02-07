package com.example.where.ui

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import android.graphics.Color as AndroidColor
import android.util.Log
import java.lang.ref.WeakReference
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

private const val TAG = "MainScreen"

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onProfileClick: () -> Unit = {},
    onNavigateToProfile: (String) -> Unit = {}
) {
    var selectedLocation by remember { mutableStateOf<LatLng?>(null) }
    var totalHeight by remember { mutableStateOf(0f) }
    var mapHeight by remember { mutableStateOf(0.4f) }
    val density = LocalDensity.current
    
    // Track composition memory usage
    SideEffect {
        MemoryTracker.logMemoryUsage("Composition")
    }
    
    // Create two ExoPlayers - one for current and one for preloading
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val currentPlayerRef = remember { mutableStateOf<ExoPlayer?>(null) }
    val preloadPlayerRef = remember { mutableStateOf<ExoPlayer?>(null) }
    val currentPlayerView = remember { mutableStateOf<WeakReference<PlayerView>?>(null) }
    
    fun createOptimizedPlayer() = ExoPlayer.Builder(context)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
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
    LaunchedEffect(viewModel.currentVideo) {
        viewModel.currentVideo?.let { video ->
            // First detach the player
            currentPlayerView.value?.get()?.player = null
            
            // Then update the current player
            currentPlayer.apply {
                    stop()
                    clearMediaItems()
                    setMediaItem(MediaItem.fromUri(video.url))
                    prepare()
                    playWhenReady = true
                }
            
            // Finally reattach the player and force a surface refresh
            currentPlayerView.value?.get()?.apply {
                hideController()
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(AndroidColor.BLACK)
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

    // Video completion listener
    LaunchedEffect(currentPlayer) {
        currentPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // Detach player before switch
                    currentPlayerView.value?.get()?.apply {
                        player = null
                        setShutterBackgroundColor(AndroidColor.BLACK)
                    }
                    viewModel.switchToNextVideo()
                }
            }
        })
    }

    // Add these state variables at the top of the MainScreen composable
    var showActualLocation by remember { mutableStateOf(false) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            viewModel.currentVideo?.location ?: LatLng(0.0, 0.0),
            2f
        )
    }

    var swipeOffset by remember { mutableStateOf(0f) }
    var isSwipeInProgress by remember { mutableStateOf(false) }

    // Track if we've made a guess on the current video
    var hasGuessedCurrentVideo by remember { mutableStateOf(false) }

    // Add these state variables at the top of MainScreen composable
    var showOverlay by remember { mutableStateOf(true) }
    var lastDragDirection by remember { mutableStateOf(0f) }
    val offsetY by animateFloatAsState(
        targetValue = if (showOverlay) 0f else -400f,
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    )

    // Effect to handle screen disposal and returns
    DisposableEffect(Unit) {
        onDispose {
            // If we've made a guess, ensure we load a new video when returning
            if (hasGuessedCurrentVideo) {
                viewModel.switchToNextVideo()
                showActualLocation = false
                selectedLocation = null
                hasGuessedCurrentVideo = false
            }
        }
    }

    // Add a new effect to handle playback state after swipe
    LaunchedEffect(showActualLocation) {
        if (!showActualLocation) { // This means we've just switched to a new video
            currentPlayer.apply {
                playWhenReady = true
                play()
            }
        }
    }

    // Function to calculate bounds that include both points
    fun calculateBounds(point1: LatLng, point2: LatLng): Pair<LatLngBounds, Float> {
        val builder = LatLngBounds.builder()
        builder.include(point1)
        builder.include(point2)
        val bounds = builder.build()
        
        // Calculate appropriate zoom level
        val width = bounds.northeast.longitude - bounds.southwest.longitude
        val height = bounds.northeast.latitude - bounds.southwest.latitude
        val zoom = when {
            width == 0.0 && height == 0.0 -> 15f // Points are the same
            width > height -> 
                (360.0 / (width * 2.5)).let { Math.log(it) / Math.log(2.0) }.toFloat()
            else -> 
                (180.0 / (height * 2.5)).let { Math.log(it) / Math.log(2.0) }.toFloat()
        }.coerceIn(2f, 15f)
        
        return bounds to zoom
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Video Player Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(1f - mapHeight)
                    .background(Color.Black)
                    .offset { IntOffset(swipeOffset.roundToInt(), 0) }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                // Use the last drag direction to determine overlay state
                                if (kotlin.math.abs(lastDragDirection) > 5) {
                                    showOverlay = lastDragDirection > 0
                                }
                            }
                        ) { _, dragAmount ->
                            // Store the drag direction and update overlay
                            lastDragDirection = dragAmount
                            if (kotlin.math.abs(dragAmount) > 5) {
                                showOverlay = dragAmount > 0
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { isSwipeInProgress = true },
                            onDragEnd = {
                                if (swipeOffset < -400 && showActualLocation) {  // Threshold for swipe
                                    // Detach player before switch
                                    currentPlayerView.value?.get()?.apply {
                                        player = null
                                        setShutterBackgroundColor(AndroidColor.BLACK)
                                    }
                                    viewModel.switchToNextVideo()
                                    showActualLocation = false
                                    selectedLocation = null
                                }
                                swipeOffset = 0f
                                isSwipeInProgress = false
                            },
                            onDragCancel = {
                                swipeOffset = 0f
                                isSwipeInProgress = false
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                if (showActualLocation) {  // Only allow swipe after guessing
                                    swipeOffset = (swipeOffset + dragAmount).coerceAtMost(0f)
                                }
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
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                setKeepScreenOn(true)
                                // Set player after configuration
                                player = currentPlayer
                            }.also { playerView ->
                                currentPlayerView.value = WeakReference(playerView)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { playerView ->
                            // Ensure player is attached during updates
                            if (playerView.player == null) {
                                playerView.player = currentPlayer
                            }
                        }
                    )
                }

                // Wrap the overlay controls in an animated Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .graphicsLayer { 
                            translationY = offsetY 
                        }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Top Row with Profile and Likes
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left side: Profile and Username
                            Row(
                                modifier = Modifier
                                    .clickable { 
                                        viewModel.currentVideo?.authorId?.let { authorId ->
                                            onNavigateToProfile(authorId)
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
                                    text = viewModel.currentVideo?.authorUsername ?: "Unknown",
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

                            // Likes Row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Likes count
                                Text(
                                    text = viewModel.formatNumber(viewModel.currentVideo?.likes ?: 0),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    modifier = Modifier
                                        .background(
                                            color = Color.Black.copy(alpha = 0.5f),
                                            shape = MaterialTheme.shapes.medium
                                        )
                                        .padding(vertical = 4.dp, horizontal = 8.dp)
                                )
                                
                                // Like Button
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f))
                                ) {
                                    IconButton(
                                        onClick = { viewModel.toggleLike() },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = "Like",
                                            tint = if (viewModel.isLiked) Color.Red else Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Comments Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Comments count
                            Text(
                                text = "0", // Placeholder for comments count
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                modifier = Modifier
                                    .background(
                                        color = Color.Black.copy(alpha = 0.5f),
                                        shape = MaterialTheme.shapes.medium
                                    )
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                            )
                            
                            // Comments Button
                            Box(
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f))
                            ) {
                                IconButton(
                                    onClick = { /* TODO: Implement comments */ },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = "Comments",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Score Display overlay at bottom of video (keep this outside the animated overlay)
                if (showActualLocation) {
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
            }

            // Draggable Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(Color.DarkGray)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            mapHeight = (mapHeight - dragAmount / totalHeight).coerceIn(0.2f, 0.8f)
                        }
                    }
            )

            // Map Section (remove swipe detection and score display)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .onSizeChanged { 
                        with(density) { totalHeight = it.height.toDp().value }
                    }
            ) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    onMapClick = { latLng ->
                        if (!showActualLocation && !isSwipeInProgress) {
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
                    if (showActualLocation && selectedLocation != null) {
                        viewModel.currentVideo?.location?.let { actualLocation ->
                            // Actual location marker
                            Marker(
                                state = MarkerState(position = actualLocation),
                                title = "Actual Location",
                                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                            )

                            // Dotted line between guess and actual location
                            Polyline(
                                points = listOf(selectedLocation!!, actualLocation),
                                pattern = listOf(Dot(), Gap(20f)),
                                color = androidx.compose.ui.graphics.Color.DarkGray,
                                width = 5f
                            )

                            // Update camera position to show both markers
                            LaunchedEffect(selectedLocation, actualLocation) {
                                val (bounds, zoom) = calculateBounds(selectedLocation!!, actualLocation)
                                cameraPositionState.animate(
                                    update = CameraUpdateFactory.newLatLngBounds(bounds, 100),
                                    durationMs = 1000
                                )
                            }
                        }
                    }
                }

                // Submit Guess Button (only show if not showing result)
                if (!showActualLocation) {
                Button(
                    onClick = { 
                        selectedLocation?.let { location ->
                            viewModel.submitGuess(location)
                                showActualLocation = true
                                hasGuessedCurrentVideo = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    enabled = selectedLocation != null
                ) {
                    Text("Submit Guess")
                    }
                }
            }
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
    }
} 