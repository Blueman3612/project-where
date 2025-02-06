package com.example.where.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.where.ui.MainScreen
import com.example.where.ui.profile.ProfileScreen
import com.example.where.ui.upload.UploadScreen

sealed class Screen(val route: String) {
    object Main : Screen("main")
    object Profile : Screen("profile")
    object Upload : Screen("upload")
    object Video : Screen("video/{videoId}") {
        fun createRoute(videoId: String) = "video/$videoId"
    }
    object Auth : Screen("auth")
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Main.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Main.route) {
            MainScreen(
                onProfileClick = {
                    navController.navigate(Screen.Profile.route)
                }
            )
        }
        
        composable(Screen.Profile.route) {
            ProfileScreen(
                onNavigateToVideo = { videoId ->
                    navController.navigate(Screen.Video.createRoute(videoId))
                },
                onNavigateToAuth = {
                    navController.navigate(Screen.Auth.route)
                }
            )
        }

        composable(Screen.Upload.route) {
            UploadScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
} 