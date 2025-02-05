package com.example.where.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var showMap by remember { mutableStateOf(false) }
    var selectedLocation by remember { mutableStateOf<LatLng?>(null) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Video Player Placeholder
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Text(
                text = "Video Player",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Overlay Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                onClick = { showMap = !showMap },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
            ) {
                Icon(
                    if (showMap) Icons.Default.VideoLibrary else Icons.Default.Place,
                    "Toggle Map",
                    tint = Color.White
                )
            }
        }

        // Map or Location Guessing UI
        if (showMap) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                color = Color.Transparent
            ) {
                Box {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = rememberCameraPositionState {
                            position = CameraPosition.fromLatLngZoom(
                                LatLng(0.0, 0.0), 2f
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
                        onClick = { /* Handle location submission */ },
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

        // Top Bar with Score/Progress
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Score: 0",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.5f)
            ),
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
} 