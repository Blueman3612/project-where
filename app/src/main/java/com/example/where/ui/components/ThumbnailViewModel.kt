package com.example.where.ui.components

import androidx.lifecycle.ViewModel
import com.example.where.data.model.Video
import com.example.where.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ThumbnailViewModel @Inject constructor(
    private val videoRepository: VideoRepository
) : ViewModel() {
    
    suspend fun generateThumbnail(video: Video): String? {
        return if (video.thumbnailUrl == null) {
            videoRepository.generateThumbnailForExistingVideo(video.url, video.id)
        } else {
            video.thumbnailUrl
        }
    }
} 