package com.example.where.auth

import android.content.Intent

interface GoogleSignInHandler {
    fun launchSignIn(signInIntent: Intent)
} 