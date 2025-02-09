package com.example.where

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.example.where.auth.AuthScreen
import com.example.where.auth.AuthViewModel
import com.example.where.auth.GoogleSignInHandler
import com.example.where.data.repository.UserRepository
import com.example.where.navigation.AppNavigation
import com.example.where.ui.theme.WhereTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity(), GoogleSignInHandler {

    private val authViewModel: AuthViewModel by viewModels()
    private lateinit var googleSignInLauncher: ActivityResultLauncher<Intent>
    
    @Inject
    lateinit var userRepository: UserRepository

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
            var isDarkMode by remember { mutableStateOf(true) }
            
            // Load saved theme preference when user is authenticated
            LaunchedEffect(authViewModel.isAuthenticated) {
                if (authViewModel.isAuthenticated) {
                    userRepository.getUserSettings()?.let { settings ->
                        isDarkMode = settings["isDarkMode"] as? Boolean ?: true
                    }
                }
            }

            WhereTheme(
                darkTheme = isDarkMode,
                onThemeUpdated = { newDarkMode ->
                    Log.d("MainActivity", "Theme toggle requested: isDarkMode = $newDarkMode")
                    isDarkMode = newDarkMode
                    // Save theme preference when changed
                    if (authViewModel.isAuthenticated) {
                        Log.d("MainActivity", "User is authenticated, saving theme preference")
                        lifecycleScope.launch {
                            val success = userRepository.updateUserSettings(isDarkMode = newDarkMode)
                            Log.d("MainActivity", "Theme preference save ${if (success) "succeeded" else "failed"}")
                        }
                    } else {
                        Log.d("MainActivity", "User not authenticated, skipping theme preference save")
                    }
                }
            ) {
                if (authViewModel.isAuthenticated) {
                    val navController = rememberNavController()
                    AppNavigation(
                        navController = navController,
                        isDarkMode = isDarkMode,
                        onThemeToggle = { 
                            Log.d("MainActivity", "Theme toggle from AppNavigation: isDarkMode = ${!isDarkMode}")
                            // Use the same theme update logic here
                            if (authViewModel.isAuthenticated) {
                                Log.d("MainActivity", "User is authenticated, saving theme preference")
                                lifecycleScope.launch {
                                    val success = userRepository.updateUserSettings(isDarkMode = !isDarkMode)
                                    Log.d("MainActivity", "Theme preference save ${if (success) "succeeded" else "failed"}")
                                }
                            }
                            isDarkMode = !isDarkMode
                        }
                    )
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