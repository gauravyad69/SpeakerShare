package io.github.gauravyad69.speakershare.data.repository.impl

import io.github.gauravyad69.speakershare.data.model.AudioQuality
import io.github.gauravyad69.speakershare.data.model.AudioSource
import io.github.gauravyad69.speakershare.data.model.HostSession
import io.github.gauravyad69.speakershare.data.model.NetworkInfo
import io.github.gauravyad69.speakershare.data.repository.HostSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple in-memory implementation of [HostSessionRepository].
 */
@Singleton
class HostSessionRepositoryImpl @Inject constructor() : HostSessionRepository {

    private val sessionState = MutableStateFlow<HostSession?>(null)

    override fun getCurrentSession(): Flow<HostSession?> = sessionState.asStateFlow()

    override suspend fun createSession(
        hostName: String,
        audioSource: AudioSource,
        quality: AudioQuality,
        networkInfo: NetworkInfo
    ): Result<HostSession> = runCatching {
        val session = HostSession(
            sessionId = UUID.randomUUID().toString(),
            sessionName = "${hostName}'s Session",
            hostName = hostName,
            audioSource = audioSource,
            quality = quality,
            isActive = false,
            startTime = System.currentTimeMillis(),
            connectedClients = emptyList(),
            networkInfo = networkInfo
        )
        sessionState.value = session
        session
    }

    override suspend fun startBroadcasting(): Result<Unit> = runCatching {
        updateSession {
            it.copy(isActive = true, startTime = System.currentTimeMillis())
        }
    }

    override suspend fun stopBroadcasting(): Result<Unit> = runCatching {
        updateSession { it.copy(isActive = false) }
    }

    override suspend fun endSession(): Result<Unit> = runCatching {
        sessionState.value = null
    }

    override suspend fun updateAudioSource(audioSource: AudioSource): Result<Unit> = runCatching {
        updateSession { it.copy(audioSource = audioSource) }
    }

    override suspend fun updateAudioQuality(quality: AudioQuality): Result<Unit> = runCatching {
        updateSession { it.copy(quality = quality) }
    }

    override suspend fun getSessionState(): HostSession? = sessionState.value

    override suspend fun isSessionActive(): Boolean = sessionState.value?.isActive == true

    private fun updateSession(transform: (HostSession) -> HostSession) {
        val current = sessionState.value ?: error("No active session")
        sessionState.update { transform(current) }
    }
}
