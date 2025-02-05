package com.example.where.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val auth: FirebaseAuth
) : ViewModel() {

    var userEmail by mutableStateOf<String?>(null)
        private set

    var uploadedVideos by mutableStateOf(0)
        private set

    var totalScore by mutableStateOf(0)
        private set

    init {
        userEmail = auth.currentUser?.email
        // TODO: Load user stats from Firestore
    }

    fun signOut() {
        auth.signOut()
    }
} 