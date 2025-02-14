package com.example.where.ui

import android.content.Context
import android.graphics.Bitmap
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
import com.example.where.util.LanguageDetector
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject
import kotlin.math.*
import kotlinx.coroutines.delay

private const val TAG = "MainViewModel"

@HiltViewModel
class MainViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val commentRepository: CommentRepository,
    private val auth: FirebaseAuth,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _currentVideoUrl = MutableStateFlow<Video?>(null)
    val currentVideoUrl: StateFlow<Video?> = _currentVideoUrl.asStateFlow()

    private val _currentVideo = MutableStateFlow<Video?>(null)
    val currentVideo: StateFlow<Video?> = _currentVideo.asStateFlow()
    
    private val _nextVideo = MutableStateFlow<Video?>(null)
    val nextVideo: StateFlow<Video?> = _nextVideo.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
        
    private val _currentScore = MutableStateFlow(0)
    val currentScore: StateFlow<Int> = _currentScore.asStateFlow()
        
    private val _lastGuessScore = MutableStateFlow<Int?>(null)
    val lastGuessScore: StateFlow<Int?> = _lastGuessScore.asStateFlow()
        
    private val _lastGuessDistance = MutableStateFlow<Double?>(null)
    val lastGuessDistance: StateFlow<Double?> = _lastGuessDistance.asStateFlow()

    private val _isLiked = MutableStateFlow(false)
    val isLiked: StateFlow<Boolean> = _isLiked.asStateFlow()

    private val _showLikeAnimation = MutableStateFlow(false)
    val showLikeAnimation: StateFlow<Boolean> = _showLikeAnimation.asStateFlow()

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    private val _commentLikes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val commentLikes: StateFlow<Map<String, Boolean>> = _commentLikes.asStateFlow()

    private val _commentReplies = MutableStateFlow<Map<String, List<Comment>>>(emptyMap())
    val commentReplies: StateFlow<Map<String, List<Comment>>> = _commentReplies.asStateFlow()

    private val _showComments = MutableStateFlow(false)
    val showComments: StateFlow<Boolean> = _showComments.asStateFlow()

    private val _isLoadingComments = MutableStateFlow(false)
    val isLoadingComments: StateFlow<Boolean> = _isLoadingComments.asStateFlow()

    private val _currentLikeCount = MutableStateFlow(0)
    val currentLikeCount: StateFlow<Int> = _currentLikeCount.asStateFlow()

    // Use the video's comments field directly
    val commentCount: Int get() = _currentVideo.value?.comments ?: 0

    // Add a job to track the comments collection
    private var commentsJob: Job? = null

    private val languageDetector = LanguageDetector(context)
    private val _detectedLanguage = MutableStateFlow<LanguageDetector.LanguageResult?>(null)
    val detectedLanguage: StateFlow<LanguageDetector.LanguageResult?> = _detectedLanguage.asStateFlow()

    private val _processingStatus = MutableStateFlow<String?>(null)
    val processingStatus: StateFlow<String?> = _processingStatus.asStateFlow()

    private val _languageHintRevealed = MutableStateFlow(false)
    val languageHintRevealed: StateFlow<Boolean> = _languageHintRevealed.asStateFlow()

    private val _debugInfoRevealed = MutableStateFlow(false)
    val debugInfoRevealed: StateFlow<Boolean> = _debugInfoRevealed.asStateFlow()

    init {
        viewModelScope.launch {
            loadNextVideo()
        }
    }

    private suspend fun loadNextVideo() {
        try {
            _isLoading.value = true
            val video = videoRepository.getRandomVideo()
            if (_currentVideo.value == null) {
                _currentVideo.value = video
                _currentVideoUrl.value = video
            } else {
                _nextVideo.value = video
            }
            // Check if current user has liked this video and set initial like count
            video?.let { 
                auth.currentUser?.uid?.let { userId ->
                    _isLiked.value = videoRepository.isVideoLiked(it.id, userId)
                }
                _currentLikeCount.value = it.likes
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error loading video: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    fun switchToNextVideo() {
        resetLanguageHint()
        viewModelScope.launch {
            _detectedLanguage.value = null
            val nextVideo = _nextVideo.value
            _currentVideo.value = nextVideo
            _currentVideoUrl.value = nextVideo
            _nextVideo.value = null
            loadNextVideo()
            
            // Reset like state and count for new video
            nextVideo?.let { video ->
                auth.currentUser?.uid?.let { userId ->
                    _isLiked.value = videoRepository.isVideoLiked(video.id, userId)
                }
                _currentLikeCount.value = video.likes
            }
        }
    }

    fun revealLanguageHint() {
        _languageHintRevealed.value = true
    }

    fun resetLanguageHint() {
        _languageHintRevealed.value = false
    }

    fun toggleDebugInfo() {
        _debugInfoRevealed.value = !_debugInfoRevealed.value
    }

    fun submitGuess(guessLocation: LatLng) {
        _currentVideo.value?.let { video ->
            val distanceMeters = calculateDistance(guessLocation, video.location)
            val distanceMiles = distanceMeters * 0.000621371 // Convert meters to miles
            _lastGuessDistance.value = distanceMiles
            
            // Calculate score based on distance and apply language hint penalty if used
            val baseScore = calculateScore(distanceMiles)
            val finalScore = if (_languageHintRevealed.value) {
                maxOf(baseScore - 1000, 0) // Apply 1000 point penalty but don't go below 0
            } else {
                baseScore
            }
            
            _lastGuessScore.value = finalScore
            _currentScore.value += finalScore
            
            _error.value = null
        } ?: run {
            _error.value = "No video loaded"
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

    // Format numbers (e.g., 1000 -> 1K)
    fun formatNumber(number: Int): String {
        return when {
            number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000.0)
            number >= 1_000 -> String.format("%.1fK", number / 1_000.0)
            else -> number.toString()
        }
    }

    // Overloaded function to handle String input
    fun formatNumber(numberStr: String): String {
        return try {
            val number = numberStr.toInt()
            formatNumber(number)
        } catch (e: NumberFormatException) {
            numberStr
        }
    }

    fun toggleLike() {
        viewModelScope.launch {
            try {
                _currentVideo.value?.let { video ->
                    auth.currentUser?.uid?.let { userId ->
                        val newLikeState = videoRepository.toggleLike(video.id, userId)
                        _isLiked.value = newLikeState
                        // Update only the like count
                        _currentLikeCount.value += if (newLikeState) 1 else -1
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error toggling like: ${e.message}")
                _error.value = "Failed to update like"
            }
        }
    }

    fun toggleComments() {
        _showComments.value = !_showComments.value
        if (_showComments.value) {
            loadComments()
        } else {
            _comments.value = emptyList()
            _commentLikes.value = emptyMap()
            _commentReplies.value = emptyMap()
        }
    }

    private fun loadComments() {
        _currentVideo.value?.let { video ->
            Log.d("MainViewModel", "Loading comments for video: ${video.id}, current comment count: ${video.comments}")
            viewModelScope.launch {
                _isLoadingComments.value = true
                try {
                    Log.d("MainViewModel", "Calling commentRepository.getCommentsForVideo")
                    val commentsList = commentRepository.getCommentsForVideo(video.id)
                    Log.d("MainViewModel", "Loaded ${commentsList.size} comments")
                    
                    if (commentsList.isEmpty()) {
                        Log.d("MainViewModel", "No comments returned from repository")
                    } else {
                        Log.d("MainViewModel", "First comment: ${commentsList.first()}")
                    }
                    
                    _comments.value = commentsList
                    
                    // Load like states for all comments
                    Log.d("MainViewModel", "Loading like states for comments")
                    val likeStates = commentsList.associate { comment ->
                        comment.id to commentRepository.isCommentLiked(comment.id)
                    }
                    Log.d("MainViewModel", "Loaded ${likeStates.size} like states")
                    _commentLikes.value = likeStates
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error loading comments: ${e.message}", e)
                    _error.value = "Failed to load comments: ${e.message}"
                } finally {
                    _isLoadingComments.value = false
                }
            }
        } ?: Log.e("MainViewModel", "No current video when trying to load comments")
    }

    fun addComment(text: String, parentId: String? = null) {
        _currentVideo.value?.let { video ->
            viewModelScope.launch {
                try {
                    val newComment = commentRepository.addComment(video.id, text, parentId)
                    if (newComment == null) {
                        _error.value = "Failed to add comment"
                    } else {
                        if (parentId == null) {
                            // Add top-level comment
                            _comments.value = listOf(newComment) + _comments.value
                        } else {
                            // Add reply to existing comment
                            val parentComment = _comments.value.find { it.id == parentId }
                            parentComment?.let {
                                val currentReplies = _commentReplies.value[parentId] ?: emptyList()
                                _commentReplies.value = _commentReplies.value + (parentId to (listOf(newComment) + currentReplies))
                                // Update the parent comment in the list with incremented reply count
                                _comments.value = _comments.value.map { comment ->
                                    if (comment.id == parentId) {
                                        comment.copy(replyCount = comment.replyCount + 1)
                                    } else {
                                        comment
                                    }
                                }
                            }
                        }
                        // Always update the video's comment count for both comments and replies
                        _currentVideo.value = video.copy(comments = video.comments + 1)
                    }
                } catch (e: Exception) {
                    _error.value = "Failed to add comment: ${e.message}"
                }
            }
        }
    }

    fun deleteComment(commentId: String) {
        viewModelScope.launch {
            try {
                val success = commentRepository.deleteComment(commentId)
                if (success) {
                    // Find if this is a top-level comment
                    val isTopLevel = _comments.value.any { it.id == commentId }
                    val deletedComment = _comments.value.find { it.id == commentId }
                    val replyCount = deletedComment?.replyCount ?: 0
                    
                    _comments.value = _comments.value.filter { it.id != commentId }
                    
                    // Update the current video with decremented comment count
                    // If it's a top-level comment, also account for its replies
                    _currentVideo.value?.let { video ->
                        val decrementAmount = if (isTopLevel) 1 + replyCount else 1
                        _currentVideo.value = video.copy(comments = video.comments - decrementAmount)
                    }
                } else {
                    _error.value = "Failed to delete comment"
                }
            } catch (e: Exception) {
                _error.value = "Failed to delete comment: ${e.message}"
            }
        }
    }

    fun migrateCommentCounts() {
        viewModelScope.launch {
            try {
                videoRepository.migrateVideoCommentCounts()
                // After migration, reload the current video to get updated count
                _currentVideo.value?.let { video ->
                    _currentVideo.value = videoRepository.getVideo(video.id)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error migrating comment counts: ${e.message}")
                _error.value = "Failed to migrate comment counts"
            }
        }
    }

    fun showLikeAnimation() {
        _showLikeAnimation.value = true
    }

    fun hideLikeAnimation() {
        _showLikeAnimation.value = false
    }

    fun toggleCommentLike(commentId: String) {
        viewModelScope.launch {
            try {
                val newLikeState = commentRepository.toggleLike(commentId)
                // Update the like state
                _commentLikes.value = _commentLikes.value + (commentId to newLikeState)
                // Update the comment's like count
                _comments.value = _comments.value.map { comment ->
                    if (comment.id == commentId) {
                        comment.copy(likes = comment.likes + if (newLikeState) 1 else -1)
                    } else {
                        comment
                    }
                }
                // Also update replies if necessary
                _commentReplies.value = _commentReplies.value.mapValues { (_, replies) ->
                    replies.map { reply ->
                        if (reply.id == commentId) {
                            reply.copy(likes = reply.likes + if (newLikeState) 1 else -1)
                        } else {
                            reply
                        }
                    }
                }
            } catch (e: Exception) {
                _error.value = "Failed to update like: ${e.message}"
            }
        }
    }

    fun loadReplies(commentId: String) {
        viewModelScope.launch {
            try {
                val replies = commentRepository.getRepliesForComment(commentId)
                _commentReplies.value = _commentReplies.value + (commentId to replies)
                // Load like states for replies
                val likeStates = replies.associate { reply ->
                    reply.id to commentRepository.isCommentLiked(reply.id)
                }
                _commentLikes.value = _commentLikes.value + likeStates
            } catch (e: Exception) {
                _error.value = "Failed to load replies: ${e.message}"
            }
        }
    }

    // Make sure to clean up when the ViewModel is cleared
    override fun onCleared() {
        super.onCleared()
        commentsJob?.cancel()
        commentsJob = null
        _comments.value = emptyList()
        _error.value = null
    }
} 