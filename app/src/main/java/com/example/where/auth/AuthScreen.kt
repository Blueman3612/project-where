package com.example.where.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Person
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.SolidColor

@Composable
fun AuthScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    onAuthSuccess: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var showRegistrationDialog by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val signInHandler = LocalContext.current as GoogleSignInHandler
    val thumbnails by viewModel.thumbnails.collectAsState()
    var currentThumbnailIndex by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    
    // Rotate through thumbnails every 8 seconds
    LaunchedEffect(thumbnails) {
        while (true) {
            kotlinx.coroutines.delay(8000)
            if (thumbnails.isNotEmpty()) {
                currentThumbnailIndex = (currentThumbnailIndex + 1) % thumbnails.size
            }
        }
    }

    LaunchedEffect(viewModel.isAuthenticated) {
        if (viewModel.isAuthenticated) {
            onAuthSuccess()
        }
    }

    LaunchedEffect(viewModel.error) {
        viewModel.error?.let {
            isLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Blurred background with crossfade transition
        if (thumbnails.isNotEmpty()) {
            Crossfade(
                targetState = currentThumbnailIndex,
                animationSpec = tween(durationMillis = 1000)
            ) { index ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumbnails[index])
                        .crossfade(false) // Disable Coil's crossfade since we're using Compose's
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(20.dp),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // Semi-transparent overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Where",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 48.sp
                ),
                color = Color.White,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Share your world",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 32.dp)
            )

            if (!showRegistrationDialog) {
                // Sign In Form
            OutlinedTextField(
                value = email,
                onValueChange = { 
                    email = it
                    emailError = null
                },
                label = { Text("Email") },
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = "Email") },
                isError = emailError != null,
                supportingText = { emailError?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier
                    .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
                        unfocusedLeadingIconColor = Color.White.copy(alpha = 0.7f),
                        unfocusedTextColor = Color.White,
                        focusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        focusedLabelColor = Color.White,
                        focusedLeadingIconColor = Color.White,
                        cursorColor = Color.White
                    )
            )

            OutlinedTextField(
                value = password,
                onValueChange = { 
                    password = it
                    passwordError = null
                },
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = "Password") },
                visualTransformation = PasswordVisualTransformation(),
                isError = passwordError != null,
                supportingText = { passwordError?.let { Text(it) } },
                modifier = Modifier
                    .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
                        unfocusedLeadingIconColor = Color.White.copy(alpha = 0.7f),
                        unfocusedTextColor = Color.White,
                        focusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        focusedLabelColor = Color.White,
                        focusedLeadingIconColor = Color.White,
                        cursorColor = Color.White
                    )
                )

                Button(
                    onClick = { 
                        if (validateInput(email, password)) {
                            isLoading = true
                            viewModel.signIn(email, password)
                        } else {
                            updateErrors(email, password, { emailError = it }, { passwordError = it })
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text("Sign In")
                }

                OutlinedButton(
                    onClick = { showRegistrationDialog = true },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text("Create Account")
            }

            OutlinedButton(
                onClick = { 
                    isLoading = true
                    viewModel.signInWithGoogle(context, signInHandler)
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text("Sign in with Google")
                }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp),
                    color = Color.White
                )
            }

            viewModel.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            Text(
                text = "By continuing, you agree to our Terms of Service and Privacy Policy",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 16.dp, start = 32.dp, end = 32.dp)
            )
        }

        // Test Users Generator Button (bottom-right corner)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            OutlinedButton(
                onClick = { 
                    scope.launch {
                        viewModel.generateTestUsers()
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White.copy(alpha = 0.5f)
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = SolidColor(Color.White.copy(alpha = 0.3f))
                ),
                modifier = Modifier.size(width = 120.dp, height = 40.dp)
            ) {
                Text(
                    "Generate Users",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }

    // Registration Dialog
    if (showRegistrationDialog) {
        var regEmail by remember { mutableStateOf("") }
        var regPassword by remember { mutableStateOf("") }
        var regUsername by remember { mutableStateOf("") }
        var regEmailError by remember { mutableStateOf<String?>(null) }
        var regPasswordError by remember { mutableStateOf<String?>(null) }
        var regUsernameError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showRegistrationDialog = false },
            title = { Text("Create Account") },
            text = {
                Column {
                    OutlinedTextField(
                        value = regEmail,
                        onValueChange = { 
                            regEmail = it
                            regEmailError = null
                        },
                        label = { Text("Email") },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = "Email") },
                        isError = regEmailError != null,
                        supportingText = { regEmailError?.let { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    OutlinedTextField(
                        value = regUsername,
                        onValueChange = { 
                            regUsername = it
                            regUsernameError = null
                        },
                        label = { Text("Username") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = "Username") },
                        isError = regUsernameError != null,
                        supportingText = { regUsernameError?.let { Text(it) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    OutlinedTextField(
                        value = regPassword,
                        onValueChange = { 
                            regPassword = it
                            regPasswordError = null
                        },
                        label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = "Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = regPasswordError != null,
                        supportingText = { regPasswordError?.let { Text(it) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            if (validateRegistration(regEmail, regPassword, regUsername)) {
                                val isUsernameAvailable = viewModel.checkUsernameAvailable(regUsername)
                                if (isUsernameAvailable) {
                                    isLoading = true
                                    viewModel.signUp(regEmail, regPassword, regUsername)
                                    showRegistrationDialog = false
                                } else {
                                    regUsernameError = "Username is already taken"
                                }
                            } else {
                                updateRegistrationErrors(
                                    regEmail, regPassword, regUsername,
                                    { regEmailError = it },
                                    { regPasswordError = it },
                                    { regUsernameError = it }
                                )
                            }
                        }
                    }
                ) {
                    Text("Create Account")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegistrationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Username Setup Dialog for Google Sign-In
    if (showUsernameDialog) {
        var username by remember { mutableStateOf("") }
        var usernameError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { /* Don't allow dismissal */ },
            title = { Text("Choose Username") },
            text = {
                Column {
                    Text(
                        "Please choose a username for your account",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { 
                            username = it
                            usernameError = null
                        },
                        label = { Text("Username") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = "Username") },
                        isError = usernameError != null,
                        supportingText = { usernameError?.let { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            if (username.isNotBlank()) {
                                val isAvailable = viewModel.checkUsernameAvailable(username)
                                if (isAvailable) {
                                    viewModel.setUsername(username)
                                    showUsernameDialog = false
                                } else {
                                    usernameError = "Username is already taken"
                                }
                            } else {
                                usernameError = "Username cannot be empty"
                            }
                        }
                    }
                ) {
                    Text("Confirm")
                }
            }
        )
    }
}

private fun validateInput(email: String, password: String): Boolean {
    return email.isNotBlank() && 
           email.contains("@") && 
           password.isNotBlank() && 
           password.length >= 6
}

private fun updateErrors(
    email: String,
    password: String,
    setEmailError: (String?) -> Unit,
    setPasswordError: (String?) -> Unit
) {
    if (email.isBlank()) {
        setEmailError("Email cannot be empty")
    } else if (!email.contains("@")) {
        setEmailError("Invalid email format")
    } else {
        setEmailError(null)
    }

    if (password.isBlank()) {
        setPasswordError("Password cannot be empty")
    } else if (password.length < 6) {
        setPasswordError("Password must be at least 6 characters")
    } else {
        setPasswordError(null)
    }
}

private fun validateRegistration(email: String, password: String, username: String): Boolean {
    return email.isNotBlank() && 
           email.contains("@") && 
           password.isNotBlank() && 
           password.length >= 6 &&
           username.isNotBlank()
}

private fun updateRegistrationErrors(
    email: String,
    password: String,
    username: String,
    setEmailError: (String?) -> Unit,
    setPasswordError: (String?) -> Unit,
    setUsernameError: (String?) -> Unit
) {
    if (email.isBlank()) {
        setEmailError("Email cannot be empty")
    } else if (!email.contains("@")) {
        setEmailError("Invalid email format")
    } else {
        setEmailError(null)
    }

    if (password.isBlank()) {
        setPasswordError("Password cannot be empty")
    } else if (password.length < 6) {
        setPasswordError("Password must be at least 6 characters")
    } else {
        setPasswordError(null)
    }

    if (username.isBlank()) {
        setUsernameError("Username cannot be empty")
    } else {
        setUsernameError(null)
    }
} 