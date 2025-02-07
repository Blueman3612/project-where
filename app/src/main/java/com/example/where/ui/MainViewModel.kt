package com.example.where.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.where.data.model.Video
import com.example.where.data.model.Comment
import com.example.where.data.repository.VideoRepository
import com.example.where.data.repository.CommentRepository
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.*
import kotlinx.coroutines.Job

@HiltViewModel
class MainViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val commentRepository: CommentRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private var _currentVideo = mutableStateOf<Video?>(null)
    val currentVideo: Video? get() = _currentVideo.value
    
    private var _nextVideo = mutableStateOf<Video?>(null)
    val nextVideo: Video? get() = _nextVideo.value
    
    private var _isLoading = mutableStateOf(false)
    val isLoading: Boolean get() = _isLoading.value

    var error by mutableStateOf<String?>(null)
        private set
        
    var currentScore by mutableStateOf(0)
        private set
        
    var lastGuessScore by mutableStateOf<Int?>(null)
        private set
        
    var lastGuessDistance by mutableStateOf<Double?>(null)
        private set

    private val _isLiked = mutableStateOf(false)
    val isLiked: Boolean get() = _isLiked.value

    // Comment-related state
    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    private val _showComments = mutableStateOf(false)
    val showComments: Boolean get() = _showComments.value

    private val _isLoadingComments = mutableStateOf(false)
    val isLoadingComments: Boolean get() = _isLoadingComments.value

    // Use the video's comments field directly
    val commentCount: Int get() = _currentVideo.value?.comments ?: 0

    // Add a job to track the comments collection
    private var commentsJob: Job? = null

    init {
        viewModelScope.launch {
            loadNextVideo()
            // Initialize random likes for all videos
            initializeRandomLikes()
        }
    }

    private suspend fun loadNextVideo() {
        try {
            _isLoading.value = true
            val video = videoRepository.getRandomVideo()
            if (_currentVideo.value == null) {
                _currentVideo.value = video
            } else {
                _nextVideo.value = video
            }
            // Check if current user has liked this video
            video?.let { 
                auth.currentUser?.uid?.let { userId ->
                    _isLiked.value = videoRepository.isVideoLiked(it.id, userId)
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error loading video: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    fun switchToNextVideo() {
        viewModelScope.launch {
            val nextVideo = _nextVideo.value
            _currentVideo.value = nextVideo
            _nextVideo.value = null
            loadNextVideo()
            
            // Reset like state for new video
            nextVideo?.let { video ->
                auth.currentUser?.uid?.let { userId ->
                    _isLiked.value = videoRepository.isVideoLiked(video.id, userId)
                }
            }
        }
    }

    fun submitGuess(guessLocation: LatLng) {
        currentVideo?.let { video ->
            val distanceMeters = calculateDistance(guessLocation, video.location)
            val distanceMiles = distanceMeters * 0.000621371 // Convert meters to miles
            lastGuessDistance = distanceMiles
            
            // Log the locations and distance for debugging
            Log.d("MainViewModel", "Guess location: ${guessLocation.latitude}, ${guessLocation.longitude}")
            Log.d("MainViewModel", "Actual location: ${video.location.latitude}, ${video.location.longitude}")
            Log.d("MainViewModel", "Distance: $distanceMiles miles")
            
            // Calculate score based on distance
            val score = calculateScore(distanceMiles)
            lastGuessScore = score
            currentScore += score
            
            error = null
        } ?: run {
            error = "No video loaded"
        }
    }
    
    private fun calculateScore(distanceMiles: Double): Int {
        return when {
            distanceMiles <= 0.0189394 -> 5000 // Full score for within 100 feet (≈ 0.0189394 miles)
            distanceMiles >= 3000.0 -> 0 // No points for guesses over 3000 miles
            else -> {
                // Using exponential decay function: a * e^(-bx) + c
                // Parameters tuned to match the required points:
                // 50 miles -> 4000 points
                // 200 miles -> 3000 points
                // 500 miles -> 2000 points
                // 1000 miles -> 1000 points
                // 2000 miles -> 500 points
                val a = 4100.0  // Initial amplitude
                val b = 0.0018  // Decay rate
                val c = 400.0   // Vertical shift

                (a * Math.exp(-b * distanceMiles) + c).toInt()
                    .coerceIn(0, 5000) // Ensure score stays within bounds
            }
        }
    }
    
    private fun calculateDistance(point1: LatLng, point2: LatLng): Double {
        val earthRadius = 6371000.0 // Earth's radius in meters
        
        // Convert to radians
        val lat1 = Math.toRadians(point1.latitude)
        val lon1 = Math.toRadians(point1.longitude)
        val lat2 = Math.toRadians(point2.latitude)
        val lon2 = Math.toRadians(point2.longitude)
        
        // Haversine formula
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        
        val a = sin(dLat / 2).pow(2) + 
                cos(lat1) * cos(lat2) * 
                sin(dLon / 2).pow(2)
        
        val c = 2 * asin(sqrt(a))
        
        return earthRadius * c
    }
    
    // Update UI to show miles instead of feet
    fun formatDistance(distance: Double): String {
        return when {
            distance < 0.1 -> String.format("%.0f feet", distance * 5280) // Show feet for very close guesses
            distance < 10 -> String.format("%.2f miles", distance) // Show 2 decimal places for close guesses
            else -> String.format("%.1f miles", distance) // Show 1 decimal place for far guesses
        }
    }

    fun initializeRandomLikes() {
        viewModelScope.launch {
            try {
                videoRepository.addRandomLikesToExistingVideos()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error initializing random likes: ${e.message}")
                error = "Failed to initialize likes"
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

    fun toggleLike() {
        viewModelScope.launch {
            try {
                currentVideo?.let { video ->
                    auth.currentUser?.uid?.let { userId ->
                        val newLikeState = videoRepository.toggleLike(video.id, userId)
                        _isLiked.value = newLikeState
                        // Update the current video's like count
                        _currentVideo.value = video.copy(
                            likes = video.likes + (if (newLikeState) 1 else -1)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error toggling like: ${e.message}")
                error = "Failed to update like"
            }
        }
    }

    fun toggleComments() {
        _showComments.value = !_showComments.value
        if (_showComments.value) {
            loadComments()
        } else {
            _comments.value = emptyList()
        }
    }

    private fun loadComments() {
        currentVideo?.let { video ->
            viewModelScope.launch {
                _isLoadingComments.value = true
                try {
                    val commentsList = commentRepository.getCommentsForVideo(video.id)
                    if (_showComments.value) {
                        _comments.value = commentsList
                    }
                } catch (e: Exception) {
                    if (_showComments.value) {
                        error = "Failed to load comments: ${e.message}"
                    }
                } finally {
                    _isLoadingComments.value = false
                }
            }
        }
    }

    fun addComment(text: String) {
        currentVideo?.let { video ->
            viewModelScope.launch {
                try {
                    val newComment = commentRepository.addComment(video.id, text)
                    if (newComment == null) {
                        error = "Failed to add comment"
                    } else {
                        _comments.value = listOf(newComment) + _comments.value
                        // Update the current video with incremented comment count
                        _currentVideo.value = video.copy(comments = video.comments + 1)
                    }
                } catch (e: Exception) {
                    error = "Failed to add comment: ${e.message}"
                }
            }
        }
    }

    fun deleteComment(commentId: String) {
        viewModelScope.launch {
            try {
                val success = commentRepository.deleteComment(commentId)
                if (success) {
                    _comments.value = _comments.value.filter { it.id != commentId }
                    // Update the current video with decremented comment count
                    currentVideo?.let { video ->
                        _currentVideo.value = video.copy(comments = video.comments - 1)
                    }
                } else {
                    error = "Failed to delete comment"
                }
            } catch (e: Exception) {
                error = "Failed to delete comment: ${e.message}"
            }
        }
    }

    fun migrateCommentCounts() {
        viewModelScope.launch {
            try {
                videoRepository.migrateVideoCommentCounts()
                // After migration, reload the current video to get updated count
                currentVideo?.let { video ->
                    _currentVideo.value = videoRepository.getVideo(video.id)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error migrating comment counts: ${e.message}")
                error = "Failed to migrate comment counts"
            }
        }
    }

    // Make sure to clean up when the ViewModel is cleared
    override fun onCleared() {
        super.onCleared()
        commentsJob?.cancel()
        commentsJob = null
        _comments.value = emptyList()
        error = null
    }
} 