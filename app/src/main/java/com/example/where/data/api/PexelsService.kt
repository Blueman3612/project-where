package com.example.where.data.api

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface PexelsService {
    @GET("videos/search")
    suspend fun searchVideos(
        @Header("Authorization") apiKey: String,
        @Query("query") query: String,
        @Query("per_page") perPage: Int = 15,
        @Query("page") page: Int = 1
    ): PexelsVideoResponse
}

data class PexelsVideoResponse(
    val page: Int,
    val per_page: Int,
    val total_results: Int,
    val videos: List<PexelsVideo>
)

data class PexelsVideo(
    val id: Int,
    val width: Int,
    val height: Int,
    val url: String,
    val image: String,
    val duration: Int,
    val video_files: List<VideoFile>
)

data class VideoFile(
    val id: Int,
    val quality: String,
    val file_type: String,
    val width: Int,
    val height: Int,
    val link: String
) 