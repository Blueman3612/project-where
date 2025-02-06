package com.example.where.ui.profile

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.where.data.model.Video
import com.example.where.data.repository.VideoRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val videoRepository: VideoRepository
) : ViewModel() {

    var userEmail by mutableStateOf<String?>(null)
        private set

    var userVideos by mutableStateOf<List<Video>>(emptyList())
        private set

    var totalScore by mutableStateOf(0)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    init {
        userEmail = auth.currentUser?.email
        loadUserStats()
    }

    private fun loadUserStats() {
        auth.currentUser?.uid?.let { userId ->
            viewModelScope.launch {
                isLoading = true
                error = null
                try {
                    // Load user's uploaded videos
                    userVideos = videoRepository.getUserVideos(userId)
                    
                    // TODO: Load total score from Firestore when implemented
                } catch (e: Exception) {
                    Log.e("ProfileViewModel", "Error loading user stats", e)
                    error = "Failed to load stats: ${e.message}"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // Format numbers (e.g., 1000 -> 1K)
    fun formatNumber(number: Int): String {
        return when {
            number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000.0)
            number >= 1_000 -> String.format("%.1fK", number / 1_000.0)
            else -> number.toString()
        }
    }

    fun signOut() {
        auth.signOut()
    }

    suspend fun generateThumbnail(video: Video): String? {
        return if (video.thumbnailUrl == null) {
            videoRepository.generateThumbnailForExistingVideo(video.url, video.id)
        } else {
            video.thumbnailUrl
        }
    }

    fun generateAllMissingThumbnails() {
        viewModelScope.launch {
            isLoading = true
            error = null
            try {
                val count = videoRepository.generateMissingThumbnails()
                Log.d("ProfileViewModel", "Generated $count thumbnails")
                // Reload videos to show new thumbnails
                loadUserStats()
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error generating thumbnails", e)
                error = "Failed to generate thumbnails: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
} 