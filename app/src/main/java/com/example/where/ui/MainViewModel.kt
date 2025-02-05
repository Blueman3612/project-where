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

    var currentVideo by mutableStateOf<Video?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set
        
    var currentScore by mutableStateOf(0)
        private set
        
    var lastGuessScore by mutableStateOf<Int?>(null)
        private set
        
    var lastGuessDistance by mutableStateOf<Double?>(null)
        private set

    init {
        loadRandomVideo()
    }

    fun loadRandomVideo() {
        viewModelScope.launch {
            isLoading = true
            error = null
            lastGuessScore = null
            lastGuessDistance = null
            try {
                // Force refresh by clearing and adding new test video
                videoRepository.clearAllVideos()
                videoRepository.addTestVideo()
                currentVideo = videoRepository.getRandomVideo()
                
                if (currentVideo == null) {
                    error = "Failed to load test video"
                } else {
                    Log.d("MainViewModel", "Successfully loaded video: ${currentVideo?.url}")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error loading video", e)
                error = "Error loading video: ${e.message}"
            } finally {
                isLoading = false
            }
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
            distanceMiles <= 50.0 -> {
                // Steeper linear dropoff for first 50 miles
                // 5000 -> 4000 points over 50 miles
                val initialDropoff = 5000 - (1000.0 * (distanceMiles / 50.0))
                initialDropoff.toInt()
            }
            else -> {
                // Gentler exponential dropoff after 50 miles
                // Starting at 4000 points at 50 miles
                // k chosen to give:
                // 500 miles -> ~3000 points
                // 1000 miles -> ~2000 points
                // 2000 miles -> ~1000 points
                // 3000 miles -> ~500 points
                val k = 0.0004
                val baseScore = 4000.0 * exp(-k * (distanceMiles - 50.0))
                baseScore.toInt().coerceAtLeast(100) // Minimum score of 100
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