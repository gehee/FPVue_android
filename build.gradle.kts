// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    id("org.jetbrains.kotlin.android") version "2.0.0-RC3" apply false
}