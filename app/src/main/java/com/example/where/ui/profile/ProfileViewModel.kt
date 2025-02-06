package com.example.where.ui.profile

import android.net.Uri
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

    init {
        loadProfile()
    }

    fun loadProfile(userId: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val targetUserId = userId ?: auth.currentUser?.uid
                if (targetUserId != null) {
                    _user.value = userRepository.getUser(targetUserId)
                    loadUserVideos(targetUserId)
                }
            } catch (e: Exception) {
                _error.value = "Failed to load profile: ${e.message}"
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
                        _videos.value = videos
                    }
            } catch (e: Exception) {
                _error.value = "Failed to load videos: ${e.message}"
                _videos.value = emptyList()
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
} 