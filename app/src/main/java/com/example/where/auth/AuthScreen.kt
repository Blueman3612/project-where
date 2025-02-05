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
    val context = LocalContext.current
    val signInHandler = LocalContext.current as GoogleSignInHandler

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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Project Where",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 32.dp)
            )

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
                    .padding(vertical = 8.dp)
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
                    .padding(vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { 
                        if (validateInput(email, password)) {
                            isLoading = true
                            viewModel.signIn(email, password)
                        } else {
                            updateErrors(email, password, { emailError = it }, { passwordError = it })
                        }
                    },
                    enabled = !isLoading
                ) {
                    Text("Sign In")
                }

                Button(
                    onClick = { 
                        if (validateInput(email, password)) {
                            isLoading = true
                            viewModel.signUp(email, password)
                        } else {
                            updateErrors(email, password, { emailError = it }, { passwordError = it })
                        }
                    },
                    enabled = !isLoading
                ) {
                    Text("Sign Up")
                }
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

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp)
                )
            }

            viewModel.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
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