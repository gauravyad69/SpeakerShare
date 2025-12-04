package io.github.gauravyad69.speakershare.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.gauravyad69.speakershare.data.repository.SettingsRepository
import io.github.gauravyad69.speakershare.media.sync.*
import io.github.gauravyad69.speakershare.services.NetworkDiscoveryService
import javax.inject.Singleton

/**
 * Hilt module for synchronized media playback dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncedMediaModule {
    
    @Provides
    @Singleton
    fun provideClockSynchronizer(): ClockSynchronizer {
        return ClockSynchronizer()
    }
    
    @Provides
    @Singleton
    fun provideSyncedFileTransfer(): SyncedFileTransfer {
        return SyncedFileTransfer()
    }
    
    @Provides
    @Singleton
    fun provideSyncedPlaybackServer(
        @ApplicationContext context: Context
    ): SyncedPlaybackServer {
        return SyncedPlaybackServer(context)
    }
    
    @Provides
    @Singleton
    fun provideSyncedPlaybackClient(
        clockSynchronizer: ClockSynchronizer
    ): SyncedPlaybackClient {
        return SyncedPlaybackClient(clockSynchronizer)
    }
    
    @Provides
    @Singleton
    fun provideSyncedPlaybackManager(
        clockSync: ClockSynchronizer,
        fileTransfer: SyncedFileTransfer,
        discoveryService: NetworkDiscoveryService,
        syncServer: SyncedPlaybackServer,
        syncClient: SyncedPlaybackClient
    ): SyncedPlaybackManager {
        return SyncedPlaybackManager(clockSync, fileTransfer, discoveryService, syncServer, syncClient)
    }
    
    @Provides
    @Singleton
    fun provideSyncedMediaPlayerFactory(
        clockSync: ClockSynchronizer,
        settingsRepository: SettingsRepository
    ): SyncedMediaPlayerFactory {
        return SyncedMediaPlayerFactory(clockSync, settingsRepository)
    }
}
