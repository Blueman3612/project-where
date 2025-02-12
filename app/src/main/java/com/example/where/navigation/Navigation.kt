package com.example.where.navigation

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Message
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
import androidx.navigation.navArgument
import androidx.media3.common.util.UnstableApi
import com.example.where.ui.MainScreen
import com.example.where.ui.profile.ProfileScreen
import com.example.where.ui.upload.UploadScreen
import com.example.where.ui.auth.AuthScreen
import com.example.where.ui.search.SearchScreen
import com.example.where.ui.video.VideoScreen
import com.example.where.ui.messages.MessagesScreen
import com.example.where.ui.components.TopBar
import com.google.firebase.auth.FirebaseAuth
import com.example.where.ui.MainViewModel
import androidx.hilt.navigation.compose.hiltViewModel

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
    object Messages : Screen("messages", "Messages", Icons.AutoMirrored.Filled.Message)
    object UserProfile : Screen("search/user/{userId}", "User Profile", Icons.Default.Person) {
        fun createRoute(userId: String) = "search/user/$userId"
    }
    object Video : Screen("video/{videoId}", "Video", Icons.Default.VideoLibrary) {
        fun createRoute(videoId: String) = "video/$videoId"
    }
    object Auth : Screen("auth", "Auth", Icons.Default.Lock)
}

@androidx.media3.common.util.UnstableApi
@Composable
fun Navigation(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    isDarkMode: Boolean,
    onThemeToggle: (Boolean) -> Unit
) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val currentScreen = when {
        currentRoute == null -> Screen.Home
        currentRoute.startsWith(Screen.UserProfile.route.split("/")[0]) -> Screen.UserProfile
        currentRoute.startsWith(Screen.Video.route.split("/")[0]) -> Screen.Video
        else -> listOf(Screen.Home, Screen.Compete, Screen.Create, Screen.Search, Screen.Profile, Screen.Messages, Screen.Auth)
            .find { it.route == currentRoute } ?: Screen.Home
    }

    // Track if we're in a conversation in Messages screen
    var isInConversation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopBar(
                title = currentScreen.title,
                showBackButton = navController.previousBackStackEntry != null,
                onBackClick = {
                    if (currentScreen == Screen.Messages && isInConversation) {
                        isInConversation = false
                    } else {
                        navController.navigateUp()
                    }
                },
                showMessagesButton = currentScreen != Screen.Messages,
                onMessagesClick = { 
                    // If we're in messages and in a conversation, close it first
                    if (currentScreen == Screen.Messages && isInConversation) {
                        isInConversation = false
                    }
                    
                    navController.navigate(Screen.Messages.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                            inclusive = currentRoute == Screen.Messages.route
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.height(56.dp)
            ) {
                listOf(Screen.Home, Screen.Compete, Screen.Create, Screen.Search, Screen.Profile).forEach { screen ->
                    val selected = when (screen) {
                        Screen.Search -> currentRoute?.startsWith("search") ?: false
                        else -> currentRoute == screen.route
                    }
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title,
                                modifier = if (screen == Screen.Create) {
                                    Modifier.size(28.dp)
                                } else {
                                    Modifier.size(24.dp)
                                }
                            )
                        },
                        selected = selected,
                        onClick = {
                            // If we're in messages and in a conversation, close it first
                            if (currentScreen == Screen.Messages && isInConversation) {
                                isInConversation = false
                            }
                            
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                    inclusive = when (screen) {
                                        Screen.Search -> currentRoute?.startsWith("search") ?: false
                                        Screen.Profile -> currentRoute?.startsWith("profile") ?: false
                                        Screen.Messages -> currentRoute == Screen.Messages.route
                                        else -> currentRoute == screen.route
                                    }
                                }
                                launchSingleTop = true
                                restoreState = true
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
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Home.route) {
                MainScreen(
                    onNavigateToProfile = { userId ->
                        navController.navigate(Screen.UserProfile.createRoute(userId))
                    }
                )
            }
            
            composable(Screen.Compete.route) {
                val viewModel: MainViewModel = hiltViewModel()
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
                            text = "Language Detection Test",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        
                        Text(
                            text = "Test language detection on videos",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Text(
                            text = "Use these buttons to test language detection on individual videos or process your entire video collection.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        // Add Language Detection Test Button
                        Button(
                            onClick = { viewModel.testLanguageDetection() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Test Language Detection")
                        }

                        // Button for processing all videos
                        Button(
                            onClick = { viewModel.processAllVideosForLanguage() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Process All Videos")
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
                    userId = FirebaseAuth.getInstance().currentUser?.uid ?: "",
                    onNavigateToVideo = { videoId ->
                        navController.navigate(Screen.Video.createRoute(videoId))
                    },
                    onNavigateToAuth = {
                        navController.navigate(Screen.Auth.route)
                    },
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToMessages = {
                        navController.navigate(Screen.Messages.route)
                    },
                    isDarkMode = isDarkMode,
                    onThemeToggle = onThemeToggle
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
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToMessages = {
                            navController.navigate(Screen.Messages.route)
                        },
                        isDarkMode = isDarkMode,
                        onThemeToggle = onThemeToggle
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

            composable(Screen.Messages.route) {
                MessagesScreen(
                    currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: "",
                    onNavigateBack = { _ -> 
                        // We don't need to handle back navigation here anymore
                        // as it's handled by the BackHandler in MessagesScreen
                    },
                    onNavigateToProfile = { userId ->
                        navController.navigate(Screen.UserProfile.createRoute(userId))
                    },
                    onConversationStateChanged = { inConversation ->
                        isInConversation = inConversation
                    },
                    shouldCloseConversation = currentScreen == Screen.Messages && !isInConversation,
                    onConversationClosed = {
                        // No need to handle conversation closed callback
                    }
                )
            }
        }
    }
} 