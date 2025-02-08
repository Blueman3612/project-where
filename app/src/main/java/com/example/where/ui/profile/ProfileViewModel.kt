package com.example.where.ui.profile

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.where.data.model.User
import com.example.where.data.model.Video
import com.example.where.data.repository.UserRepository
import com.example.where.data.repository.VideoRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val videoRepository: VideoRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private val _isFollowedByUser = MutableStateFlow(false)
    val isFollowedByUser: StateFlow<Boolean> = _isFollowedByUser.asStateFlow()

    private val _followerCount = MutableStateFlow(0)
    val followerCount: StateFlow<Int> = _followerCount.asStateFlow()

    private val _followingCount = MutableStateFlow(0)
    val followingCount: StateFlow<Int> = _followingCount.asStateFlow()

    private val _navigateToMessages = MutableStateFlow<String?>(null)
    val navigateToMessages = _navigateToMessages.asStateFlow()

    init {
        // Remove the default loadProfile() call from init
        // We'll let the screen handle it with the correct user ID
    }

    fun loadProfile(userId: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            // Reset state before loading new profile
            _user.value = null
            _videos.value = emptyList()
            _isFollowing.value = false
            _isFollowedByUser.value = false
            _followerCount.value = 0
            _followingCount.value = 0
            
            try {
                // Use the provided userId directly instead of falling back to current user
                val targetUserId = userId ?: return@launch
                Log.d("ProfileViewModel", "Loading profile for user: $targetUserId")
                // Load user data first
                val userData = userRepository.getUser(targetUserId)
                _user.value = userData
                
                // Then load additional data
                loadUserVideos(targetUserId)
                loadFollowCounts(targetUserId)
                
                // Check follow relationships if viewing another user's profile
                if (targetUserId != auth.currentUser?.uid && auth.currentUser != null) {
                    val currentUserId = auth.currentUser!!.uid
                    Log.d("ProfileViewModel", "Checking follow status - Current user: $currentUserId, Target user: $targetUserId")
                    
                    _isFollowing.value = userRepository.isFollowing(currentUserId, targetUserId)
                    Log.d("ProfileViewModel", "Current user following target: ${_isFollowing.value}")
                    
                    _isFollowedByUser.value = userRepository.isFollowing(targetUserId, currentUserId)
                    Log.d("ProfileViewModel", "Target user following current: ${_isFollowedByUser.value}")
                }
            } catch (e: Exception) {
                _error.value = "Failed to load profile: ${e.message}"
                Log.e("ProfileViewModel", "Error loading profile", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadUserVideos(userId: String) {
        viewModelScope.launch {
            try {
                videoRepository.getUserVideos(userId)
                    .catch { e ->
                        _error.value = "Failed to load videos: ${e.message}"
                        _videos.value = emptyList()
                    }
                    .collect { videos ->
                        // Sort videos by createdAt timestamp in descending order (newest first)
                        _videos.value = videos.sortedByDescending { it.createdAt }
                    }
            } catch (e: Exception) {
                _error.value = "Failed to load videos: ${e.message}"
                _videos.value = emptyList()
            }
        }
    }

    private suspend fun loadFollowCounts(userId: String) {
        try {
            _followerCount.value = userRepository.getFollowerCount(userId)
            _followingCount.value = userRepository.getFollowingCount(userId)
        } catch (e: Exception) {
            Log.e("ProfileViewModel", "Error loading follow counts: ${e.message}")
        }
    }

    fun toggleFollow() {
        viewModelScope.launch {
            try {
                val targetUserId = user.value?.id ?: return@launch
                val success = if (_isFollowing.value) {
                    userRepository.unfollowUser(targetUserId)
                } else {
                    userRepository.followUser(targetUserId)
                }
                
                if (success) {
                    _isFollowing.value = !_isFollowing.value
                    // Update follower count
                    _followerCount.value = if (_isFollowing.value) {
                        _followerCount.value + 1
                    } else {
                        _followerCount.value - 1
                    }
                }
            } catch (e: Exception) {
                _error.value = "Failed to ${if (_isFollowing.value) "unfollow" else "follow"} user: ${e.message}"
            }
        }
    }

    fun updateProfile(
        bio: String? = null,
        profilePictureUri: Uri? = null
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val updatedUser = userRepository.updateProfile(
                    bio = bio,
                    profilePictureUri = profilePictureUri
                )
                if (updatedUser != null) {
                    _user.value = updatedUser
                    _error.value = null
                } else {
                    _error.value = "Failed to update profile"
                }
            } catch (e: Exception) {
                _error.value = "Failed to update profile: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun startConversation() {
        viewModelScope.launch {
            try {
                val currentUserId = auth.currentUser?.uid ?: return@launch
                val targetUserId = user.value?.id ?: return@launch

                // Create conversation ID (consistent ordering for both users)
                val conversationId = "$currentUserId-$targetUserId".takeIf { it < "$targetUserId-$currentUserId" }
                    ?: "$targetUserId-$currentUserId"

                // Check if conversation already exists
                val existingConversation = userRepository.getConversation(conversationId)
                if (existingConversation == null) {
                    // Create new conversation
                    userRepository.createConversation(
                        participants = listOf(currentUserId, targetUserId),
                        isApproved = true // Auto-approve since they follow each other
                    )
                }

                // Navigate to messages screen
                _navigateToMessages.value = conversationId
            } catch (e: Exception) {
                _error.value = "Failed to start conversation: ${e.message}"
            }
        }
    }

    fun clearNavigateToMessages() {
        _navigateToMessages.value = null
    }
} 