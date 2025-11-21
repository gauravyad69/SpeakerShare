package io.github.gauravyad69.speakershare.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.gauravyad69.speakershare.data.repository.AudioStreamRepository
import io.github.gauravyad69.speakershare.data.repository.ClientConnectionRepository
import io.github.gauravyad69.speakershare.data.repository.HostSessionRepository
import io.github.gauravyad69.speakershare.data.repository.SettingsRepository
import io.github.gauravyad69.speakershare.data.repository.UserSettingsRepository
import io.github.gauravyad69.speakershare.data.repository.impl.AudioStreamRepositoryImpl
import io.github.gauravyad69.speakershare.data.repository.impl.ClientConnectionRepositoryImpl
import io.github.gauravyad69.speakershare.data.repository.impl.HostSessionRepositoryImpl
import javax.inject.Singleton

/**
 * Binds repository interfaces to their concrete implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindUserSettingsRepository(
        impl: SettingsRepository
    ): UserSettingsRepository

    @Binds
    @Singleton
    abstract fun bindClientConnectionRepository(
        impl: ClientConnectionRepositoryImpl
    ): ClientConnectionRepository

    @Binds
    @Singleton
    abstract fun bindHostSessionRepository(
        impl: HostSessionRepositoryImpl
    ): HostSessionRepository

    @Binds
    @Singleton
    abstract fun bindAudioStreamRepository(
        impl: AudioStreamRepositoryImpl
    ): AudioStreamRepository
}
