# Project Where

A TikTok-style mobile app with location guessing features similar to GeoGuessr. Users can watch short videos and try to guess where they were recorded.

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Backend**: Firebase
  - Authentication
  - Firestore
  - Storage
  - Cloud Messaging
  - App Distribution
- **Dependencies**:
  - ExoPlayer for video playback
  - Google Maps Compose
  - Hilt for dependency injection
  - Coil for image loading

## Setup Instructions

1. Open the project in Android Studio
2. Sync project with Gradle files
3. Create a Firebase project and add your `google-services.json` to the `app` directory
4. Run the app using an emulator or physical device

## Firebase Setup

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Create a new project
3. Add an Android app with package name `com.projectwhere.app`
4. Download `google-services.json` and place it in the `app` directory
5. Enable required Firebase services:
   - Authentication
   - Firestore Database
   - Storage
   - Cloud Messaging

## Development

The project uses the latest Android development best practices:
- MVVM architecture
- Dependency injection with Hilt
- Jetpack Compose for modern UI development
- Kotlin Coroutines for asynchronous operations

## Testing

Run tests using:
```bash
./gradlew test        # Unit tests
./gradlew connectedAndroidTest  # Instrumentation tests
``` 