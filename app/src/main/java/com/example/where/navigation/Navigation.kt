package com.example.where.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.where.ui.MainScreen
import com.example.where.ui.profile.ProfileScreen
import com.example.where.ui.upload.UploadScreen
import com.example.where.ui.auth.AuthScreen
import com.example.where.ui.search.SearchScreen
import com.example.where.ui.components.TopBar
import com.example.where.ui.video.VideoScreen

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Compete : Screen("compete", "Compete", Icons.Default.EmojiEvents)
    object Create : Screen("create", "Create", Icons.Default.AddCircle)
    object Search : Screen("search", "Search", Icons.Default.Search)
    object Profile : Screen("profile", "Profile", Icons.Default.Person)
    
    // Other screens not in bottom nav
    object UserProfile : Screen("search/user/{userId}", "User Profile", Icons.Default.Person) {
        fun createRoute(userId: String) = "search/user/$userId"
    }
    object Video : Screen("video/{videoId}", "Video", Icons.Default.VideoLibrary) {
        fun createRoute(videoId: String) = "video/$videoId"
    }
    object Auth : Screen("auth", "Auth", Icons.Default.Lock)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Home.route
) {
    val bottomNavItems = listOf(
        Screen.Home,
        Screen.Compete,
        Screen.Create,
        Screen.Search,
        Screen.Profile
    )
    
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    // Determine if we're in the search section (either search or user profile)
    val isInSearchSection = currentRoute?.startsWith("search") ?: false

    Scaffold(
        topBar = {
            TopBar(
                canNavigateBack = navController.previousBackStackEntry != null,
                onNavigateBack = { navController.popBackStack() }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.height(56.dp) // Set fixed compact height
            ) {
                bottomNavItems.forEach { screen ->
                    val selected = when (screen) {
                        Screen.Search -> isInSearchSection
                        else -> currentRoute == screen.route
                    }
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title,
                                modifier = if (screen == Screen.Create) {
                                    Modifier.size(28.dp)  // Slightly smaller than before but still prominent
                                } else {
                                    Modifier.size(24.dp)
                                }
                            )
                        },
                        selected = selected,
                        onClick = {
                            when (screen) {
                                Screen.Search -> {
                                    // If already in search section, pop back to search
                                    if (isInSearchSection) {
                                        navController.popBackStack(Screen.Search.route, false)
                                    } else {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                }
                                else -> {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                composable(Screen.Home.route) {
                    MainScreen(
                        onNavigateToProfile = { userId ->
                            navController.navigate(Screen.UserProfile.createRoute(userId))
                        },
                        onNavigateBack = {
                            if (navController.previousBackStackEntry != null) {
                                navController.popBackStack()
                            }
                        }
                    )
                }
                
                composable(Screen.Compete.route) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.EmojiEvents,
                                contentDescription = "Trophy Icon",
                                modifier = Modifier.size(80.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            
                            Text(
                                text = "Compete Mode",
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            
                            Text(
                                text = "Coming Soon",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            Text(
                                text = "Challenge your friends and compete with players worldwide in exciting location-guessing battles. Stay tuned for updates!",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                AssistChip(
                                    onClick = { /* TODO */ },
                                    label = { Text("Global Leaderboard") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Leaderboard,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                                
                                AssistChip(
                                    onClick = { /* TODO */ },
                                    label = { Text("Friend Battles") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Groups,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }
                        }
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
                    SearchScreen(
                        onNavigateToProfile = { userId ->
                            navController.navigate(Screen.UserProfile.createRoute(userId))
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

                composable(
                    route = Screen.UserProfile.route,
                    arguments = listOf(
                        navArgument("userId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val userId = backStackEntry.arguments?.getString("userId")
                    if (userId != null) {
                        ProfileScreen(
                            userId = userId,
                            onNavigateToVideo = { videoId ->
                                navController.navigate(Screen.Video.createRoute(videoId))
                            },
                            onNavigateToAuth = {
                                navController.navigate(Screen.Auth.route)
                            },
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }

                composable(
                    route = Screen.Video.route,
                    arguments = listOf(
                        navArgument("videoId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val videoId = backStackEntry.arguments?.getString("videoId")
                    if (videoId != null) {
                        VideoScreen(
                            videoId = videoId,
                            onNavigateBack = {
                                navController.popBackStack()
                            },
                            onNavigateToProfile = { userId ->
                                navController.navigate(Screen.UserProfile.createRoute(userId))
                            }
                        )
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