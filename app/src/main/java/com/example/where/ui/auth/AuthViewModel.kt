package com.example.where.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.where.data.repository.VideoRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _thumbnails = MutableStateFlow<List<String>>(emptyList())
    val thumbnails: StateFlow<List<String>> = _thumbnails.asStateFlow()

    init {
        loadUserThumbnails()
    }

    private fun loadUserThumbnails() {
        viewModelScope.launch {
            try {
                videoRepository.getUserVideos("WUc04bsneQRktwOgHILRXR63V643")
                    .collect { videos ->
                        _thumbnails.value = videos
                            .mapNotNull { it.thumbnailUrl }
                            .filter { it.isNotEmpty() }
                    }
            } catch (e: Exception) {
                // Handle error if needed
            }
        }
    }

    fun signIn() {
        // TODO: Implement Google Sign-In
    }
} 