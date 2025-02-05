package com.example.where.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.where.data.model.Video
import com.example.where.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val videoRepository: VideoRepository
) : ViewModel() {

    var currentVideo by mutableStateOf<Video?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    init {
        loadRandomVideo()
    }

    fun loadRandomVideo() {
        viewModelScope.launch {
            isLoading = true
            error = null
            try {
                currentVideo = videoRepository.getRandomVideo()
                if (currentVideo == null) {
                    // No videos found, add test videos
                    videoRepository.addTestVideo()
                    // Try loading again
                    currentVideo = videoRepository.getRandomVideo()
                    if (currentVideo == null) {
                        error = "Failed to load test videos"
                    }
                }
            } catch (e: Exception) {
                error = "Error loading video: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
} 