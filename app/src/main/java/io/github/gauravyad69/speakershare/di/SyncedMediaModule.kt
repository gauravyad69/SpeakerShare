package io.github.gauravyad69.speakershare.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
    fun provideSyncedPlaybackManager(
        clockSync: ClockSynchronizer,
        fileTransfer: SyncedFileTransfer,
        discoveryService: NetworkDiscoveryService
    ): SyncedPlaybackManager {
        return SyncedPlaybackManager(clockSync, fileTransfer, discoveryService)
    }
    
    @Provides
    @Singleton
    fun provideSyncedMediaPlayerFactory(
        clockSync: ClockSynchronizer
    ): SyncedMediaPlayerFactory {
        return SyncedMediaPlayerFactory(clockSync)
    }
}
