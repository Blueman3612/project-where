package com.example.where.ui.upload

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.where.data.repository.VideoRepository
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val auth: FirebaseAuth,
    @ApplicationContext private val context: Context
) : ViewModel() {

    var selectedVideoUri by mutableStateOf<Uri?>(null)
        private set

    var selectedLocation by mutableStateOf<LatLng?>(null)
        private set

    var isUploading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set
        
    var uploadComplete by mutableStateOf(false)
        private set

    fun setSelectedVideo(uri: Uri) {
        selectedVideoUri = uri
        error = null
    }

    fun updateSelectedLocation(location: LatLng) {
        selectedLocation = location
        error = null
    }

    fun parseCoordinatesFromClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

        if (text.isNullOrBlank()) {
            error = "Clipboard is empty"
            return
        }

        try {
            // Remove any non-essential characters and split the string
            val cleanText = text.replace("[^0-9.,\\-\\s]".toRegex(), " ")
                .trim()
                .replace("\\s+".toRegex(), " ")

            // Try different coordinate formats
            val coordinates = when {
                // Format: "lat, long"
                cleanText.contains(",") -> {
                    val parts = cleanText.split(",").map { it.trim() }
                    if (parts.size == 2) {
                        Pair(parts[0].toDouble(), parts[1].toDouble())
                    } else null
                }
                // Format: "lat long"
                cleanText.contains(" ") -> {
                    val parts = cleanText.split(" ")
                    if (parts.size == 2) {
                        Pair(parts[0].toDouble(), parts[1].toDouble())
                    } else null
                }
                else -> null
            }

            if (coordinates == null) {
                error = "Could not parse coordinates from clipboard"
                return
            }

            val (lat, lng) = coordinates
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                error = "Invalid coordinates: latitude must be between -90 and 90, longitude between -180 and 180"
                return
            }

            updateSelectedLocation(LatLng(lat, lng))
            error = null
        } catch (e: Exception) {
            Log.e("UploadViewModel", "Error parsing coordinates", e)
            error = "Invalid coordinate format. Please use 'latitude, longitude' or 'latitude longitude'"
        }
    }

    fun uploadVideo() {
        val videoUri = selectedVideoUri
        val location = selectedLocation
        val userId = auth.currentUser?.uid

        if (videoUri == null || location == null || userId == null) {
            error = "Please select a video and location"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            isUploading = true
            error = null
            uploadComplete = false
            
            try {
                // Check video size before upload
                val videoSize = videoUri.let { uri ->
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { 
                        it.statSize 
                    } ?: 0L
                }

                // If video is too large (>50MB), show error
                if (videoSize > 50 * 1024 * 1024) {
                    withContext(Dispatchers.Main) {
                        error = "Video is too large. Please select a video under 50MB."
                    }
                    return@launch
                }

                // Upload in chunks to avoid memory pressure
                videoRepository.uploadVideo(
                    videoUri = videoUri,
                    location = location,
                    userId = userId
                )

                withContext(Dispatchers.Main) {
                    // Reset state after successful upload
                    selectedVideoUri = null
                    selectedLocation = null
                    uploadComplete = true
                }
            } catch (e: Exception) {
                Log.e("UploadViewModel", "Error uploading video", e)
                withContext(Dispatchers.Main) {
                    error = e.message ?: "Failed to upload video"
                    uploadComplete = false
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isUploading = false
                }
            }
        }
    }
} 