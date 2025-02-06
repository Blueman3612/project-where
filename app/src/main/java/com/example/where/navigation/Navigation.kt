package com.example.where.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.where.ui.MainScreen
import com.example.where.ui.profile.ProfileScreen
import com.example.where.ui.upload.UploadScreen
import com.example.where.ui.auth.AuthScreen

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Discover : Screen("discover", "Discover", Icons.Default.Explore)
    object Compete : Screen("compete", "Compete", Icons.Default.EmojiEvents)
    object Create : Screen("create", "Create", Icons.Default.AddCircle)
    object Search : Screen("search", "Search", Icons.Default.Search)
    object Profile : Screen("profile", "Profile", Icons.Default.Person)
    
    // Other screens not in bottom nav
    object Video : Screen("video/{videoId}", "Video", Icons.Default.VideoLibrary) {
        fun createRoute(videoId: String) = "video/$videoId"
    }
    object Auth : Screen("auth", "Auth", Icons.Default.Lock)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Discover.route
) {
    val bottomNavItems = listOf(
        Screen.Discover,
        Screen.Compete,
        Screen.Create,
        Screen.Search,
        Screen.Profile
    )
    
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                bottomNavItems.forEach { screen ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title,
                                modifier = if (screen == Screen.Create) {
                                    Modifier.size(32.dp)  // Larger icon for Create button
                                } else {
                                    Modifier.size(24.dp)
                                }
                            )
                        },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            NavHost(
                navController = navController,
                startDestination = startDestination
            ) {
                composable(Screen.Discover.route) {
                    MainScreen(
                        onProfileClick = {
                            navController.navigate(Screen.Profile.route)
                        }
                    )
                }
                
                composable(Screen.Compete.route) {
                    // Placeholder for Compete screen
                    Box(modifier = Modifier) {
                        Text("Coming Soon: Compete")
                    }
                }
                
                composable(Screen.Create.route) {
                    UploadScreen(
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
                
                composable(Screen.Search.route) {
                    // Placeholder for Search screen
                    Box(modifier = Modifier) {
                        Text("Coming Soon: Search")
                    }
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

                composable(Screen.Video.route) {
                    // Video screen will be implemented later
                    Box(modifier = Modifier) {
                        Text("Video Player Coming Soon")
                    }
                }

                composable(Screen.Auth.route) {
                    AuthScreen(
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
} 