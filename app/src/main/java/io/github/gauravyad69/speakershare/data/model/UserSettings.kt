package io.github.gauravyad69.speakershare.data.model

/**
 * Persistent user preferences and app configuration.
 * Lifecycle: Persisted in SharedPreferences, loaded on app start
 */
data class UserSettings(
    val displayName: String,
    val defaultAudioSource: AudioSource = AudioSource.MICROPHONE,
    val defaultQuality: AudioQuality = AudioQuality(),
    val autoStartHost: Boolean = false,
    val keepScreenOn: Boolean = false,
    val showNetworkMetrics: Boolean = false,
    val maxClients: Int = 0         // 0 = unlimited
)
