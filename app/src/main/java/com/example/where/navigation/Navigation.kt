package com.example.where.navigation

import androidx.compose.foundation.layout.*
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
import androidx.navigation.navArgument
import com.example.where.ui.MainScreen
import com.example.where.ui.profile.ProfileScreen
import com.example.where.ui.upload.UploadScreen
import com.example.where.ui.auth.AuthScreen
import com.example.where.ui.search.SearchScreen
import com.example.where.ui.video.VideoScreen
import com.example.where.ui.messages.MessagesScreen
import com.example.where.ui.components.TopBar
import com.google.firebase.auth.FirebaseAuth

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
    object Messages : Screen("messages", "Messages", Icons.Default.Message)
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
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    isDarkMode: Boolean = false,
    onThemeToggle: () -> Unit = {}
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

    // State for tracking if we're in a conversation
    var inConversation by remember { mutableStateOf(false) }
    val isInMessages = currentRoute == Screen.Messages.route

    // State for signaling conversation close
    var shouldCloseConversation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopBar(
                canNavigateBack = navController.previousBackStackEntry != null,
                onNavigateBack = {
                    android.util.Log.d("Navigation", "TopBar back pressed, isInMessages: $isInMessages, inConversation: $inConversation")
                    if (isInMessages && inConversation) {
                        // Signal to MessagesScreen to close the conversation
                        android.util.Log.d("Navigation", "In messages and conversation, signaling to close conversation")
                        shouldCloseConversation = true
                    } else {
                        android.util.Log.d("Navigation", "Not in messages or conversation, executing popBackStack")
                        navController.popBackStack()
                    }
                },
                onMessagesClick = { navController.navigate(Screen.Messages.route) }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.height(56.dp)
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
                                    Modifier.size(28.dp)
                                } else {
                                    Modifier.size(24.dp)
                                }
                            )
                        },
                        selected = selected,
                        onClick = {
                            // First pop any open screens
                            if (navController.previousBackStackEntry != null) {
                                navController.popBackStack()
                            }

                            // Then navigate to the selected screen
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    launchSingleTop = true
                                    restoreState = true
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
                        userId = FirebaseAuth.getInstance().currentUser?.uid ?: "",
                        onNavigateToVideo = { videoId ->
                            navController.navigate(Screen.Video.createRoute(videoId))
                        },
                        onNavigateToAuth = {
                            navController.navigate(Screen.Auth.route)
                        },
                        onNavigateBack = { _ -> navController.popBackStack() },
                        onNavigateToMessages = {
                            navController.navigate(Screen.Messages.route)
                        },
                        isDarkMode = isDarkMode,
                        onThemeToggle = { checked -> onThemeToggle() }
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
                            onNavigateBack = { _ -> navController.popBackStack() },
                            onNavigateToMessages = {
                                navController.navigate(Screen.Messages.route)
                            },
                            isDarkMode = isDarkMode,
                            onThemeToggle = { checked -> onThemeToggle() }
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
                    // Reset the close signal when leaving Messages screen
                    DisposableEffect(Unit) {
                        onDispose {
                            shouldCloseConversation = false
                            inConversation = false
                        }
                    }

                    MessagesScreen(
                        currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: "",
                        onNavigateBack = { isInConv -> 
                            android.util.Log.d("Navigation", "MessagesScreen onNavigateBack called with inConversation: $isInConv")
                            inConversation = isInConv
                            if (!isInConv && !shouldCloseConversation) {  // Only navigate back if not closing conversation
                                android.util.Log.d("Navigation", "Executing popBackStack because not in conversation")
                                navController.popBackStack()
                            } else {
                                android.util.Log.d("Navigation", "In conversation, not executing popBackStack")
                            }
                        },
                        onNavigateToProfile = { userId ->
                            navController.navigate(Screen.UserProfile.createRoute(userId))
                        },
                        shouldCloseConversation = shouldCloseConversation,
                        onConversationClosed = {
                            android.util.Log.d("Navigation", "Conversation closed, resetting states")
                            shouldCloseConversation = false
                            inConversation = false  // Make sure to update the conversation state
                        }
                    )
                }
            }
        }
    }
} 