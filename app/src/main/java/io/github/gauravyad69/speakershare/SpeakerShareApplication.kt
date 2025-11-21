package io.github.gauravyad69.speakershare

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for SpeakerShare app.
 * Annotated with @HiltAndroidApp to enable Hilt dependency injection.
 */
@HiltAndroidApp
class SpeakerShareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Application initialization code here if needed
    }
}
