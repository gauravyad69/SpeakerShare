package io.github.gauravyad69.speakershare

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for SpeakerShare app.
 * Annotated with @HiltAndroidApp to enable Hilt dependency injection.
 */
@HiltAndroidApp
class SpeakerShareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // For release builds, you can plant a crash-reporting tree:
        // else { Timber.plant(CrashReportingTree()) }
    }
}
