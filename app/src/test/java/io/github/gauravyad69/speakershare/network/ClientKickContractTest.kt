package io.github.gauravyad69.speakershare.network

import org.junit.Test
import org.junit.Assert.*
import io.github.gauravyad69.speakershare.network.api.impl.HostApiHandlerImpl
import io.github.gauravyad69.speakershare.network.api.*
import kotlinx.coroutines.test.runTest

/**
 * Contract test for POST /clients/{clientId}/kick endpoint
 * Tests the API contract for kicking clients from a host session.
 * 
 * TDD: This test MUST fail initially, then pass after implementation.
 */
class ClientKickContractTest {
    
    private val hostApiHandler = HostApiHandlerImpl()
    
    @Test
    fun `POST clients kick - success response`() = runTest {
        // Given: A valid client kick request
        val clientId = "test-client-123"
        val request = ClientKickRequest(
            clientId = clientId,
            reason = "Requested by host"
        )
        
        // When: Making kick request
        val response = hostApiHandler.kickClient(request)
        
        // Then: Should return success response
        assertEquals(clientId, response.clientId)
        assertTrue(response.kicked)
        assertEquals("Client kicked successfully", response.message)
    }
    
    @Test
    fun `POST clients kick - client not found`() = runTest {
        // Given: A request for non-existent client
        val request = ClientKickRequest(
            clientId = "non-existent-client",
            reason = "Test kick"
        )
        
        // When/Then: Should throw appropriate exception
        try {
            hostApiHandler.kickClient(request)
            fail("Expected BadRequestException for non-existent client")
        } catch (e: BadRequestException) {
            assertEquals(404, e.statusCode)
            assertTrue(e.message.contains("Client not found"))
        }
    }
    
    @Test
    fun `POST clients kick - invalid client ID`() = runTest {
        // Given: A request with invalid client ID
        val request = ClientKickRequest(
            clientId = "",
            reason = "Test kick"
        )
        
        // When/Then: Should throw validation exception
        try {
            hostApiHandler.kickClient(request)
            fail("Expected BadRequestException for invalid client ID")
        } catch (e: BadRequestException) {
            assertEquals(400, e.statusCode)
            assertTrue(e.message.contains("Invalid client ID"))
        }
    }
    
    @Test
    fun `POST clients kick - missing reason`() = runTest {
        // Given: A request without reason
        val request = ClientKickRequest(
            clientId = "test-client-123",
            reason = ""
        )
        
        // When: Making kick request (reason optional)
        val response = hostApiHandler.kickClient(request)
        
        // Then: Should still succeed with default reason
        assertEquals("test-client-123", response.clientId)
        assertTrue(response.kicked)
        assertNotNull(response.message)
    }
}