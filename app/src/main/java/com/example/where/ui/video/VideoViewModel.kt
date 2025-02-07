package com.example.where.ui.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.where.data.model.Video
import com.example.where.data.model.Comment
import com.example.where.data.repository.VideoRepository
import com.example.where.data.repository.CommentRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class VideoViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val commentRepository: CommentRepository,
    private val auth: FirebaseAuth
) : ViewModel() {
    
    var video by mutableStateOf<Video?>(null)
        private set
        
    var isLoading by mutableStateOf(false)
        private set
        
    var error by mutableStateOf<String?>(null)
        private set
        
    var isLiked by mutableStateOf(false)
        private set

    // Comment-related state
    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments = _comments.asStateFlow()

    var showComments by mutableStateOf(false)
        private set

    var isLoadingComments by mutableStateOf(false)
        private set

    val currentUserId: String?
        get() = auth.currentUser?.uid
    
    fun loadVideo(videoId: String) {
        viewModelScope.launch {
            try {
                isLoading = true
                error = null
                
                video = videoRepository.getVideo(videoId)
                
                // Check if the current user has liked this video
                auth.currentUser?.uid?.let { userId ->
                    isLiked = videoRepository.isVideoLiked(videoId, userId)
                }
            } catch (e: Exception) {
                error = "Failed to load video: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
    
    fun toggleLike() {
        viewModelScope.launch {
            try {
                video?.let { currentVideo ->
                    auth.currentUser?.uid?.let { userId ->
                        val newLikeState = videoRepository.toggleLike(currentVideo.id, userId)
                        isLiked = newLikeState
                        // Update the video's like count
                        video = currentVideo.copy(
                            likes = currentVideo.likes + (if (newLikeState) 1 else -1)
                        )
                    }
                }
            } catch (e: Exception) {
                error = "Failed to update like: ${e.message}"
            }
        }
    }

    fun toggleComments() {
        showComments = !showComments
        if (showComments) {
            loadComments()
        } else {
            _comments.value = emptyList()
        }
    }

    private fun loadComments() {
        video?.let { currentVideo ->
            viewModelScope.launch {
                isLoadingComments = true
                try {
                    val commentsList = commentRepository.getCommentsForVideo(currentVideo.id)
                    if (showComments) {
                        _comments.value = commentsList
                    }
                } catch (e: Exception) {
                    error = "Failed to load comments: ${e.message}"
                } finally {
                    isLoadingComments = false
                }
            }
        }
    }

    fun addComment(text: String) {
        video?.let { currentVideo ->
            viewModelScope.launch {
                try {
                    val newComment = commentRepository.addComment(currentVideo.id, text)
                    if (newComment == null) {
                        error = "Failed to add comment"
                    } else {
                        _comments.value = listOf(newComment) + _comments.value
                        // Update the video with incremented comment count
                        video = currentVideo.copy(comments = currentVideo.comments + 1)
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
                    // Update the video with decremented comment count
                    video?.let { currentVideo ->
                        video = currentVideo.copy(comments = currentVideo.comments - 1)
                    }
                } else {
                    error = "Failed to delete comment"
                }
            } catch (e: Exception) {
                error = "Failed to delete comment: ${e.message}"
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

    // Format timestamp into a readable date
    fun formatTimestamp(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("hh:mm a, M/d/yyyy", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
} 