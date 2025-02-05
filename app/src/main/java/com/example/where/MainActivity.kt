package com.example.where

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.example.where.auth.AuthScreen
import com.example.where.auth.AuthViewModel
import com.example.where.auth.GoogleSignInHandler
import com.example.where.ui.MainScreen
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity(), GoogleSignInHandler {

    private val authViewModel: AuthViewModel by viewModels()
    private lateinit var googleSignInLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                account.idToken?.let { token ->
                    authViewModel.handleGoogleSignInResult(token)
                }
            } catch (e: ApiException) {
                authViewModel.handleGoogleSignInError(e.message ?: "Unknown error")
            }
        }

        setContent {
            MaterialTheme {
                if (authViewModel.isAuthenticated) {
                    MainScreen()
                } else {
                    AuthScreen(
                        onAuthSuccess = { /* State is handled by AuthViewModel */ }
                    )
                }
            }
        }
    }

    override fun launchSignIn(signInIntent: Intent) {
        googleSignInLauncher.launch(signInIntent)
    }
} 