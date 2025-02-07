package com.example.where.ui.upload

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    viewModel: UploadViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val selectedLocation = viewModel.selectedLocation

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setSelectedVideo(it) }
    }

    LaunchedEffect(viewModel.uploadComplete) {
        if (viewModel.uploadComplete) {
            onNavigateBack()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {  // Wrap everything in a Box for overlay
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Map for location selection
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val cameraPositionState = rememberCameraPositionState {
                        position = CameraPosition.fromLatLngZoom(
                            selectedLocation ?: LatLng(0.0, 0.0),
                            if (selectedLocation == null) 2f else 8f
                        )
                    }

                    // Update camera position when location changes
                    LaunchedEffect(selectedLocation) {
                        selectedLocation?.let {
                            cameraPositionState.position = CameraPosition.fromLatLngZoom(it, 8f)
                        }
                    }

                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        onMapClick = { latLng ->
                            viewModel.updateSelectedLocation(latLng)
                        }
                    ) {
                        selectedLocation?.let { location ->
                            Marker(
                                state = MarkerState(position = location),
                                title = "Video Location"
                            )
                        }
                    }

                    // Location selection hint
                    if (selectedLocation == null) {
                        Card(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Tap on the map to select video location",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                OutlinedButton(
                                    onClick = { viewModel.parseCoordinatesFromClipboard(context) }
                                ) {
                                    Icon(
                                        Icons.Default.ContentPaste,
                                        contentDescription = "Paste Coordinates",
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text("Paste Coordinates")
                                }
                            }
                        }
                    }
                }

                // Upload section
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Selected video info
                        viewModel.selectedVideoUri?.let { uri ->
                            Text(
                                text = "Selected video: ${uri.lastPathSegment}",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }

                        // Video selection button
                        Button(
                            onClick = { videoPickerLauncher.launch("video/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.VideoFile,
                                contentDescription = "Select Video",
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(if (viewModel.selectedVideoUri == null) "Select Video" else "Change Video")
                        }

                        // Upload button
                        Button(
                            onClick = { viewModel.uploadVideo() },
                            enabled = viewModel.selectedVideoUri != null && selectedLocation != null && !viewModel.isUploading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (viewModel.isUploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    Icons.Default.CloudUpload,
                                    contentDescription = "Upload",
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text("Upload Video")
                            }
                        }
                    }
                }

                // Error message
                viewModel.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Loading overlay
            if (viewModel.isUploading) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Uploading video...",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
} 