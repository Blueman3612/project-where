package com.example.where.auth

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.where.R
import com.example.where.data.repository.VideoRepository
import com.example.where.data.repository.UserRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val videoRepository: VideoRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    var isAuthenticated by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var googleSignInClient: GoogleSignInClient? = null
    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    private val _thumbnails = MutableStateFlow<List<String>>(emptyList())
    val thumbnails: StateFlow<List<String>> = _thumbnails.asStateFlow()

    init {
        setupAuthStateListener()
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
                Log.e("AuthViewModel", "Error loading thumbnails", e)
            }
        }
    }

    private fun setupAuthStateListener() {
        authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            isAuthenticated = firebaseAuth.currentUser != null
        }.also { listener ->
            auth.addAuthStateListener(listener)
        }
    }

    override fun onCleared() {
        super.onCleared()
        authStateListener?.let { listener ->
            auth.removeAuthStateListener(listener)
        }
        googleSignInClient = null
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            error = null
            try {
                auth.signInWithEmailAndPassword(email, password).await()
                isAuthenticated = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = when {
                    e.message?.contains("no user record", ignoreCase = true) == true ||
                    e.message?.contains("password is invalid", ignoreCase = true) == true ->
                        "Invalid email or password"
                    else -> e.message ?: "Sign in failed"
                }
                Log.e("AuthViewModel", "Sign in failed", e)
            }
        }
    }

    suspend fun checkUsernameAvailable(username: String): Boolean {
        return userRepository.checkUsernameAvailable(username)
    }

    fun signUp(email: String, password: String, username: String) {
        viewModelScope.launch {
            error = null
            try {
                // First create the authentication account
                val authResult = auth.createUserWithEmailAndPassword(email, password).await()
                
                // Then create the user document in Firestore
                authResult.user?.let { firebaseUser ->
                    val user = userRepository.createUser(email, username)
                    if (user != null) {
                        isAuthenticated = true
                    } else {
                        // If Firestore creation fails, delete the auth account to maintain consistency
                        firebaseUser.delete().await()
                        error = "Failed to create user profile"
                    }
                } ?: run {
                    error = "Failed to create account"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = when {
                    e.message?.contains("email already in use", ignoreCase = true) == true -> 
                        "Email is already registered"
                    e.message?.contains("weak password", ignoreCase = true) == true ->
                        "Password is too weak"
                    else -> e.message ?: "Sign up failed"
                }
                Log.e("AuthViewModel", "Sign up failed", e)
            }
        }
    }

    fun setUsername(username: String) {
        viewModelScope.launch {
            error = null
            try {
                userRepository.updateUsername(auth.currentUser?.uid ?: return@launch, username)
                isAuthenticated = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message
                Log.e("AuthViewModel", "Username update failed", e)
            }
        }
    }

    fun signInWithGoogle(context: Context, signInHandler: GoogleSignInHandler) {
        viewModelScope.launch {
            error = null
            try {
                // Clean up any existing client
                googleSignInClient?.signOut()?.await()
                auth.signOut()

                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(context.getString(R.string.default_web_client_id))
                    .requestEmail()
                    .build()

                googleSignInClient = GoogleSignIn.getClient(context, gso)
                
                // Start fresh sign in
                googleSignInClient?.signOut()?.await()
                val signInIntent = googleSignInClient?.signInIntent ?: throw Exception("Failed to create sign-in intent")
                signInHandler.launchSignIn(signInIntent)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errorMsg = "Failed to initialize Google Sign-In: ${e.message}"
                error = errorMsg
                Log.e("AuthViewModel", errorMsg, e)
            }
        }
    }

    fun handleGoogleSignInResult(idToken: String) {
        viewModelScope.launch {
            try {
                Log.d("AuthViewModel", "Handling Google Sign-In result with token length: ${idToken.length}")
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                auth.signInWithCredential(credential).await()
                Log.d("AuthViewModel", "Successfully signed in with Google")
                isAuthenticated = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errorMsg = "Google Sign-In credential exchange failed: ${e.message}"
                error = errorMsg
                Log.e("AuthViewModel", errorMsg, e)
            }
        }
    }

    fun handleGoogleSignInError(message: String) {
        val errorMsg = "Google sign in failed: $message"
        error = errorMsg
        Log.e("AuthViewModel", errorMsg)
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                auth.signOut()
                googleSignInClient?.signOut()?.await()
                isAuthenticated = false
                googleSignInClient = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("AuthViewModel", "Error during sign out", e)
            }
        }
    }
} 