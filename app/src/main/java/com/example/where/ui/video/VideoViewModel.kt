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

    var showLikeAnimation by mutableStateOf(false)
        private set

    private val _currentLikeCount = mutableStateOf(0)
    val currentLikeCount: Int get() = _currentLikeCount.value

    // Comment-related state
    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments = _comments.asStateFlow()

    private val _commentLikes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val commentLikes = _commentLikes.asStateFlow()

    private val _commentReplies = MutableStateFlow<Map<String, List<Comment>>>(emptyMap())
    val commentReplies = _commentReplies.asStateFlow()

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
                
                // Check if the current user has liked this video and set initial like count
                video?.let { currentVideo ->
                    auth.currentUser?.uid?.let { userId ->
                        isLiked = videoRepository.isVideoLiked(currentVideo.id, userId)
                    }
                    _currentLikeCount.value = currentVideo.likes
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
                        // Update only the like count
                        _currentLikeCount.value += if (newLikeState) 1 else -1
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
                        // Load like states for all comments
                        val likeStates = commentsList.associate { comment ->
                            comment.id to commentRepository.isCommentLiked(comment.id)
                        }
                        _commentLikes.value = likeStates
                    }
                } catch (e: Exception) {
                    error = "Failed to load comments: ${e.message}"
                } finally {
                    isLoadingComments = false
                }
            }
        }
    }

    fun addComment(text: String, parentId: String? = null) {
        video?.let { currentVideo ->
            viewModelScope.launch {
                try {
                    val newComment = commentRepository.addComment(currentVideo.id, text, parentId)
                    if (newComment == null) {
                        error = "Failed to add comment"
                    } else {
                        if (parentId == null) {
                            // Add top-level comment
                            _comments.value = listOf(newComment) + _comments.value
                            // Update the video with incremented comment count
                            video = currentVideo.copy(comments = currentVideo.comments + 1)
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
                    }
                } catch (e: Exception) {
                    error = "Failed to add comment: ${e.message}"
                }
            }
        }
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
                error = "Failed to update like: ${e.message}"
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
                error = "Failed to load replies: ${e.message}"
            }
        }
    }

    fun deleteComment(commentId: String) {
        viewModelScope.launch {
            try {
                val success = commentRepository.deleteComment(commentId)
                if (success) {
                    // Find if this is a top-level comment or a reply
                    val isTopLevel = _comments.value.any { it.id == commentId }
                    if (isTopLevel) {
                        _comments.value = _comments.value.filter { it.id != commentId }
                        // Remove any replies to this comment
                        _commentReplies.value = _commentReplies.value - commentId
                        // Update the video with decremented comment count
                        video?.let { currentVideo ->
                            video = currentVideo.copy(comments = currentVideo.comments - 1)
                        }
                    } else {
                        // This is a reply, find its parent and update the reply count
                        _commentReplies.value = _commentReplies.value.mapValues { (parentId, replies) ->
                            val filteredReplies = replies.filter { it.id != commentId }
                            if (filteredReplies.size < replies.size) {
                                // Update the parent comment's reply count
                                _comments.value = _comments.value.map { comment ->
                                    if (comment.id == parentId) {
                                        comment.copy(replyCount = comment.replyCount - 1)
                                    } else {
                                        comment
                                    }
                                }
                            }
                            filteredReplies
                        }
                    }
                    // Remove like state for the deleted comment
                    _commentLikes.value = _commentLikes.value - commentId
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

    fun showLikeAnimation() {
        showLikeAnimation = true
    }

    fun hideLikeAnimation() {
        showLikeAnimation = false
    }
} 