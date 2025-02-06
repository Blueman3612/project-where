package com.example.where.data.model

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.GeoPoint

private const val TAG = "Video"

data class Video(
    val id: String,
    val url: String,
    val thumbnailUrl: String?,
    val location: LatLng,
    val title: String?,
    val description: String?,
    val authorId: String,
    val source: VideoSource,
    val likes: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "url" to url,
        "thumbnailUrl" to (thumbnailUrl ?: ""),
        "location" to mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude
        ),
        "title" to (title ?: ""),
        "description" to (description ?: ""),
        "authorId" to authorId,
        "source" to source.name,
        "likes" to likes,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any>): Video? {
            try {
                Log.d(TAG, "Converting map to Video: $map")
                
                // Handle different location formats
                val location = when (val locationData = map["location"]) {
                    is Map<*, *> -> {
                        Log.d(TAG, "Location is a Map: $locationData")
                        LatLng(
                            (locationData["latitude"] as? Number)?.toDouble() ?: 0.0,
                            (locationData["longitude"] as? Number)?.toDouble() ?: 0.0
                        )
                    }
                    is GeoPoint -> {
                        Log.d(TAG, "Location is a GeoPoint: $locationData")
                        LatLng(locationData.latitude, locationData.longitude)
                    }
                    else -> {
                        Log.e(TAG, "Unknown location format: ${locationData?.javaClass}, defaulting to (0,0)")
                        LatLng(0.0, 0.0)
                    }
                }

                // Safely extract all fields with null checks
                val id = map["id"] as? String ?: return null.also { 
                    Log.e(TAG, "Missing required field: id") 
                }
                val url = map["url"] as? String ?: return null.also { 
                    Log.e(TAG, "Missing required field: url") 
                }
                val authorId = map["authorId"] as? String ?: return null.also { 
                    Log.e(TAG, "Missing required field: authorId") 
                }
                val sourceStr = map["source"] as? String ?: return null.also { 
                    Log.e(TAG, "Missing required field: source") 
                }
                
                return Video(
                    id = id,
                    url = url,
                    thumbnailUrl = map["thumbnailUrl"] as? String,
                    location = location,
                    title = map["title"] as? String,
                    description = map["description"] as? String,
                    authorId = authorId,
                    source = try {
                        VideoSource.valueOf(sourceStr)
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Invalid source value: $sourceStr")
                        VideoSource.PEXELS // Default to PEXELS if invalid
                    },
                    likes = (map["likes"] as? Number)?.toInt() ?: 0,
                    createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing video data: ${e.message}")
                return null
            }
        }
    }
}

enum class VideoSource {
    PEXELS,
    USER_UPLOAD
} 