package io.github.gauravyad69.speakershare.data.repository.impl

import io.github.gauravyad69.speakershare.data.model.ClientAudioSettings
import io.github.gauravyad69.speakershare.data.model.ClientConnection
import io.github.gauravyad69.speakershare.data.model.ConnectionStatus
import io.github.gauravyad69.speakershare.data.model.NetworkMetrics
import io.github.gauravyad69.speakershare.data.repository.ClientConnectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory implementation of [ClientConnectionRepository].
 * Keeps track of connected clients for the lifetime of the process.
 */
@Singleton
class ClientConnectionRepositoryImpl @Inject constructor() : ClientConnectionRepository {

    private val clients = MutableStateFlow<List<ClientConnection>>(emptyList())

    override fun getConnectedClients(): Flow<List<ClientConnection>> = clients.asStateFlow()

    override suspend fun getClient(clientId: String): ClientConnection? =
        clients.value.firstOrNull { it.clientId == clientId }

    override suspend fun addClient(
        clientId: String,
        clientName: String,
        ipAddress: String,
        audioSettings: ClientAudioSettings
    ): Result<ClientConnection> = runCatching {
        if (clients.value.any { it.clientId == clientId }) {
            error("Client $clientId already connected")
        }

        val connection = ClientConnection(
            clientId = clientId,
            clientName = clientName,
            ipAddress = ipAddress,
            connectionTime = System.currentTimeMillis(),
            status = ConnectionStatus.CONNECTED,
            audioSettings = audioSettings,
            networkMetrics = NetworkMetrics(
                latency = 0,
                packetLoss = 0f,
                bandwidth = 0
            )
        )

        clients.update { it + connection }
        connection
    }

    override suspend fun removeClient(clientId: String): Result<Unit> = runCatching {
        clients.update { list -> list.filterNot { it.clientId == clientId } }
    }

    override suspend fun kickClient(clientId: String): Result<Unit> = removeClient(clientId)

    override suspend fun updateClientStatus(clientId: String, status: ConnectionStatus): Result<Unit> =
        runCatching {
            updateClient(clientId) { it.copy(status = status) }
        }

    override suspend fun updateClientAudioSettings(
        clientId: String,
        settings: ClientAudioSettings
    ): Result<Unit> = runCatching {
        updateClient(clientId) { it.copy(audioSettings = settings) }
    }

    override suspend fun updateClientMetrics(
        clientId: String,
        metrics: NetworkMetrics
    ): Result<Unit> = runCatching {
        updateClient(clientId) { it.copy(networkMetrics = metrics) }
    }

    override suspend fun getClientCount(): Int = clients.value.size

    override suspend fun clearAllClients(): Result<Unit> = runCatching {
        clients.value = emptyList()
    }

    override suspend fun getClientsByStatus(status: ConnectionStatus): List<ClientConnection> =
        clients.value.filter { it.status == status }

    private fun updateClient(
        clientId: String,
        transform: (ClientConnection) -> ClientConnection
    ) {
        var updated = false
        clients.update { list ->
            list.map {
                if (it.clientId == clientId) {
                    updated = true
                    transform(it)
                } else {
                    it
                }
            }
        }
        if (!updated) {
            error("Client $clientId not found")
        }
    }
}
