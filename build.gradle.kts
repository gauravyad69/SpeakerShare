// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// Workaround for JDK 21 compatibility issue with Android Gradle Plugin
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-source")
        options.compilerArgs.add("11")
        options.compilerArgs.add("-target")
        options.compilerArgs.add("11")
    }
}