package com.example.where.ui.profile

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    var uploadedVideos by mutableStateOf(0)
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
                    uploadedVideos = videoRepository.getUserUploadCount(userId)
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

    fun signOut() {
        auth.signOut()
    }
} 