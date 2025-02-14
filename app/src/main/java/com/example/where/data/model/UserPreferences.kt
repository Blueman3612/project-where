package com.example.where.data.model

data class UserPreferences(
    val userId: String,
    val preferredRegions: Map<String, Float>,
    val preferredLanguages: Map<String, Float>,
    val preferredCategories: Map<String, Float>,
    val skillLevel: Float,
    val activeHours: List<Int>,
    val lastUpdated: Long
) 