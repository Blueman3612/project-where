pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    versionCatalogs {
        create("libs") {
            library("hilt-android", "com.google.dagger:hilt-android:2.48")
            library("hilt-compiler", "com.google.dagger:hilt-android-compiler:2.48")
        }
    }
}

rootProject.name = "ProjectWhere"
include(":app") 