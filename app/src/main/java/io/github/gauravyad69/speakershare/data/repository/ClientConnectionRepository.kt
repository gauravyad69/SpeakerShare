package io.github.gauravyad69.speakershare.data.repository

import io.github.gauravyad69.speakershare.data.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing client connections.
 * Handles connected clients state and operations.
 */
interface ClientConnectionRepository {
    
    /**
     * Get all connected clients as a flow for reactive updates
     */
    fun getConnectedClients(): Flow<List<ClientConnection>>
    
    /**
     * Get specific client by ID
     */
    suspend fun getClient(clientId: String): ClientConnection?
    
    /**
     * Add new client connection
     */
    suspend fun addClient(
        clientId: String,
        clientName: String,
        ipAddress: String,
        audioSettings: ClientAudioSettings = ClientAudioSettings()
    ): Result<ClientConnection>
    
    /**
     * Remove client connection
     */
    suspend fun removeClient(clientId: String): Result<Unit>
    
    /**
     * Kick client (host action)
     */
    suspend fun kickClient(clientId: String): Result<Unit>
    
    /**
     * Update client connection status
     */
    suspend fun updateClientStatus(clientId: String, status: ConnectionStatus): Result<Unit>
    
    /**
     * Update client audio settings
     */
    suspend fun updateClientAudioSettings(clientId: String, settings: ClientAudioSettings): Result<Unit>
    
    /**
     * Update client network metrics
     */
    suspend fun updateClientMetrics(clientId: String, metrics: NetworkMetrics): Result<Unit>
    
    /**
     * Get current client count
     */
    suspend fun getClientCount(): Int
    
    /**
     * Clear all clients (session end)
     */
    suspend fun clearAllClients(): Result<Unit>
    
    /**
     * Get clients by status
     */
    suspend fun getClientsByStatus(status: ConnectionStatus): List<ClientConnection>
}
