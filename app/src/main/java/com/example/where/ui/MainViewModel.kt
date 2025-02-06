package com.example.where.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.where.data.model.Video
import com.example.where.data.repository.VideoRepository
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.*

@HiltViewModel
class MainViewModel @Inject constructor(
    private val videoRepository: VideoRepository
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
            } else {
                _nextVideo.value = video
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error loading video: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    fun switchToNextVideo() {
        viewModelScope.launch {
            _currentVideo.value = _nextVideo.value
            _nextVideo.value = null
            loadNextVideo()
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
} 