package com.example.where.auth

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: FirebaseAuth
) : ViewModel() {

    var isAuthenticated by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private lateinit var googleSignInClient: GoogleSignInClient

    init {
        auth.addAuthStateListener { firebaseAuth ->
            isAuthenticated = firebaseAuth.currentUser != null
        }
    }

    fun signIn(email: String, password: String) {
        error = null
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                isAuthenticated = true
            }
            .addOnFailureListener {
                error = it.message
                Log.e("AuthViewModel", "Sign in failed", it)
            }
    }

    fun signUp(email: String, password: String) {
        error = null
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                isAuthenticated = true
            }
            .addOnFailureListener {
                error = it.message
                Log.e("AuthViewModel", "Sign up failed", it)
            }
    }

    fun signInWithGoogle(context: Context, signInHandler: GoogleSignInHandler) {
        error = null
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken("1085576090755-fiat625hn2sr9segru35ggtn31vuq2sa.apps.googleusercontent.com")
                .requestEmail()
                .build()

            googleSignInClient = GoogleSignIn.getClient(context, gso)
            
            // Check if there's an existing Google Sign-In
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account?.idToken != null) {
                handleGoogleSignInResult(account.idToken!!)
                return
            }

            val signInIntent = googleSignInClient.signInIntent
            signInHandler.launchSignIn(signInIntent)
        } catch (e: Exception) {
            error = "Failed to initialize Google Sign-In: ${e.message}"
            Log.e("AuthViewModel", "Google Sign-In initialization failed", e)
        }
    }

    fun handleGoogleSignInResult(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                isAuthenticated = true
            }
            .addOnFailureListener {
                error = it.message
                Log.e("AuthViewModel", "Google Sign-In failed", it)
            }
    }

    fun handleGoogleSignInError(message: String) {
        error = "Google sign in failed: $message"
        Log.e("AuthViewModel", "Google Sign-In error: $message")
    }

    fun signOut() {
        auth.signOut()
        if (::googleSignInClient.isInitialized) {
            googleSignInClient.signOut()
        }
        isAuthenticated = false
    }
} 