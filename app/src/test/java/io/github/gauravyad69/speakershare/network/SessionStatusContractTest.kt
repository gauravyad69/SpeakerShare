package io.github.gauravyad69.speakershare.network

import org.junit.Test
import org.junit.Assert.*
import io.github.gauravyad69.speakershare.network.api.impl.HostApiHandlerImpl
import io.github.gauravyad69.speakershare.network.api.*
import kotlinx.coroutines.test.runTest

/**
 * Contract test for GET /session/status endpoint
 * Tests the API contract for retrieving host session status.
 * 
 * TDD: This test MUST fail initially, then pass after implementation.
 */
class SessionStatusContractTest {
    
    private val hostApiHandler = HostApiHandlerImpl()
    
    @Test
    fun `GET session status - active session`() = runTest {
        // Given: An active host session
        val sessionId = "test-session-123"
        
        // When: Making session status request
        val response = hostApiHandler.getSessionStatus(sessionId)
        
        // Then: Should return active session status
        assertEquals(sessionId, response.sessionId)
        assertEquals("ACTIVE", response.status)
        assertTrue(response.isActive)
        assertNotNull(response.hostName)
        assertNotNull(response.audioSource)
        assertNotNull(response.connectedClients)
        assertTrue(response.startTime > 0)
        assertTrue(response.uptime > 0)
    }
    
    @Test
    fun `GET session status - inactive session`() = runTest {
        // Given: An inactive session ID
        val sessionId = "inactive-session-456"
        
        // When: Making session status request
        val response = hostApiHandler.getSessionStatus(sessionId)
        
        // Then: Should return inactive session status
        assertEquals(sessionId, response.sessionId)
        assertEquals("INACTIVE", response.status)
        assertFalse(response.isActive)
        assertEquals(0, response.connectedClients.size)
        assertEquals(0, response.uptime)
    }
    
    @Test
    fun `GET session status - session not found`() = runTest {
        // Given: A non-existent session ID
        val sessionId = "non-existent-session"
        
        // When/Then: Should throw not found exception
        try {
            hostApiHandler.getSessionStatus(sessionId)
            fail("Expected BadRequestException for non-existent session")
        } catch (e: BadRequestException) {
            assertEquals(404, e.statusCode)
            assertTrue(e.message.contains("Session not found"))
        }
    }
    
    @Test
    fun `GET session status - invalid session ID`() = runTest {
        // Given: An invalid session ID (empty)
        val sessionId = ""
        
        // When/Then: Should throw validation exception
        try {
            hostApiHandler.getSessionStatus(sessionId)
            fail("Expected BadRequestException for invalid session ID")
        } catch (e: BadRequestException) {
            assertEquals(400, e.statusCode)
            assertTrue(e.message.contains("Invalid session ID"))
        }
    }
    
    @Test
    fun `GET session status - session with multiple clients`() = runTest {
        // Given: A session with multiple connected clients
        val sessionId = "multi-client-session"
        
        // When: Making session status request
        val response = hostApiHandler.getSessionStatus(sessionId)
        
        // Then: Should return status with client list
        assertEquals(sessionId, response.sessionId)
        assertEquals("ACTIVE", response.status)
        assertTrue(response.isActive)
        assertTrue(response.connectedClients.isNotEmpty())
        
        // Verify client information structure
        val firstClient = response.connectedClients.first()
        assertNotNull(firstClient.clientId)
        assertNotNull(firstClient.clientName)
        assertNotNull(firstClient.ipAddress)
        assertTrue(firstClient.connectedAt > 0)
    }
    
    @Test
    fun `GET session status - session metrics included`() = runTest {
        // Given: An active session with metrics
        val sessionId = "metrics-session"
        
        // When: Making session status request
        val response = hostApiHandler.getSessionStatus(sessionId)
        
        // Then: Should include performance metrics
        assertEquals(sessionId, response.sessionId)
        assertNotNull(response.metrics)
        assertTrue(response.metrics!!.latency >= 0)
        assertTrue(response.metrics!!.packetLoss >= 0.0f)
        assertTrue(response.metrics!!.bandwidth > 0)
        assertTrue(response.metrics!!.cpuUsage >= 0.0f)
        assertTrue(response.metrics!!.memoryUsage > 0)
    }
}