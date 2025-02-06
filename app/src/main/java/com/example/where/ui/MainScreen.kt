package com.example.where.ui

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import android.graphics.Color as AndroidColor
import android.util.Log
import java.lang.ref.WeakReference

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
    onProfileClick: () -> Unit = {}
) {
    var selectedLocation by remember { mutableStateOf<LatLng?>(null) }
    var totalHeight by remember { mutableStateOf(0f) }
    var mapHeight by remember { mutableStateOf(0.4f) }
    val density = LocalDensity.current
    
    // Track composition memory usage
    SideEffect {
        MemoryTracker.logMemoryUsage("Composition")
    }
    
    // ExoPlayer setup with memory tracking
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val playerRef = remember { mutableStateOf<ExoPlayer?>(null) }
    val exoPlayer = remember { 
        MemoryTracker.logMemoryUsage("ExoPlayer Creation")
        ExoPlayer.Builder(context)
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
            }.also {
                playerRef.value?.release()
                playerRef.value = it
            }
    }

    // Single DisposableEffect to handle all cleanup
    DisposableEffect(lifecycleOwner) {
        var playerView by mutableStateOf<PlayerView?>(null)
        
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    Log.d(TAG, "Lifecycle: ON_PAUSE")
                    MemoryTracker.logMemoryUsage("ON_PAUSE")
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    Log.d(TAG, "Lifecycle: ON_RESUME")
                    MemoryTracker.logMemoryUsage("ON_RESUME")
                    if (viewModel.currentVideo != null) {
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    Log.d(TAG, "Lifecycle: ON_STOP")
                    MemoryTracker.logMemoryUsage("ON_STOP")
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    Log.d(TAG, "Lifecycle: ON_DESTROY")
                    MemoryTracker.logMemoryUsage("ON_DESTROY")
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            Log.d(TAG, "DisposableEffect: Cleaning up resources")
            MemoryTracker.logMemoryUsage("Before Cleanup")
            
            lifecycleOwner.lifecycle.removeObserver(observer)
            playerView?.player = null
            playerView = null
            
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.release()
            playerRef.value = null
            
            MemoryTracker.logMemoryUsage("After Cleanup")
            System.gc()
        }
    }

    // Update video when currentVideo changes
    LaunchedEffect(viewModel.currentVideo) {
        try {
            Log.d(TAG, "Loading new video")
            MemoryTracker.logMemoryUsage("Before Video Load")
            
            viewModel.currentVideo?.let { video ->
                exoPlayer.apply {
                    stop()
                    clearMediaItems()
                    setMediaItem(MediaItem.fromUri(video.url))
                    prepare()
                    playWhenReady = true
                }
                selectedLocation = null
            } ?: run {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
            
            MemoryTracker.logMemoryUsage("After Video Load")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading video", e)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Video Player Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(1f - mapHeight)
                    .background(Color.Black)
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
                                player = exoPlayer
                                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                                useController = false // Disable default controller
                                setKeepContentOnPlayerReset(true)
                                setShutterBackgroundColor(AndroidColor.BLACK)
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                setKeepScreenOn(true)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Overlay Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { /* Handle profile click */ },
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                    ) {
                        Icon(Icons.Default.AccountCircle, "Profile", tint = Color.White)
                    }

                    IconButton(
                        onClick = { /* Handle like */ },
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                    ) {
                        Icon(Icons.Default.Favorite, "Like", tint = Color.White)
                    }

                    IconButton(
                        onClick = { viewModel.loadRandomVideo() },
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                    ) {
                        Icon(Icons.Default.Refresh, "Next Video", tint = Color.White)
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

            // Map Section
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
                    cameraPositionState = rememberCameraPositionState {
                        position = CameraPosition.fromLatLngZoom(
                            viewModel.currentVideo?.location ?: LatLng(0.0, 0.0),
                            2f
                        )
                    },
                    onMapClick = { latLng ->
                        selectedLocation = latLng
                    }
                ) {
                    selectedLocation?.let { location ->
                        Marker(
                            state = MarkerState(position = location),
                            title = "Selected Location"
                        )
                    }
                }

                // Submit Guess Button
                Button(
                    onClick = { 
                        selectedLocation?.let { location ->
                            viewModel.submitGuess(location)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    enabled = selectedLocation != null
                ) {
                    Text("Submit Guess")
                }
                
                // Show guess results
                viewModel.lastGuessScore?.let { score ->
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .fillMaxWidth(0.8f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                            .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = viewModel.lastGuessDistance?.let { distance ->
                                    viewModel.formatDistance(distance)
                                } ?: "Unknown distance",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Points: +$score",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Button(
                                onClick = { 
                                    viewModel.loadRandomVideo()
                                },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text("Next Video")
                            }
                        }
                    }
                }
            }
        }

        // Top Bar with Score/Progress
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Score: ${viewModel.currentScore}",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = onProfileClick,
                    modifier = Modifier
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Profile",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.5f)
            ),
            modifier = Modifier.align(Alignment.TopCenter)
        )

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