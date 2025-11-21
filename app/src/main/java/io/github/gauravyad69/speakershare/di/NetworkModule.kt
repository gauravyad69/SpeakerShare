package io.github.gauravyad69.speakershare.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.gauravyad69.speakershare.network.api.HostApiHandler
import io.github.gauravyad69.speakershare.network.api.impl.HostApiHandlerImpl
import javax.inject.Singleton

/** Provides network-layer dependencies. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {

    @Binds
    @Singleton
    abstract fun bindHostApiHandler(
        impl: HostApiHandlerImpl
    ): HostApiHandler
}
