buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
        classpath("com.google.gms:google-services:4.4.1")
        classpath("com.google.firebase:firebase-appdistribution-gradle:4.2.0")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.48")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
} 