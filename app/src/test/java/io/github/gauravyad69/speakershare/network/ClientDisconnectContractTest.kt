package io.github.gauravyad69.speakershare.network

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
// Import factory functions from TestDataClasses
import io.github.gauravyad69.speakershare.network.*

/**
 * Contract test for POST /clients/{clientId}/disconnect endpoint
 * 
 * This test MUST FAIL initially before implementation.
 * Tests client disconnection endpoint (can be called by client or host).
 * 
 * Expected Response Format (200):
 * {
 *   "status": "DISCONNECTED",
 *   "reason": "CLIENT_REQUEST"
 * }
 */
class ClientDisconnectContractTest {

    @Test
    fun `POST clients disconnect succeeds for valid client ID`() = runTest {
        // Arrange
        val clientId = "550e8400-e29b-41d4-a716-446655440001"

        // Act - This will FAIL because HostApiHandler doesn't exist yet
        val hostApiHandler = createHostApiHandler() // Will fail - doesn't exist
        val response = hostApiHandler.disconnectClient(clientId)

        // Assert - Contract validation
        assertNotNull("Response should not be null", response)
        assertEquals("Status should be DISCONNECTED", "DISCONNECTED", response.status)
        assertTrue("Reason should be valid", 
            response.reason in listOf("CLIENT_REQUEST", "HOST_REQUEST", "TIMEOUT", "ERROR"))
    }

    @Test
    fun `POST clients disconnect returns 404 for non-existent client`() = runTest {
        // Arrange - Non-existent client ID
        val nonExistentClientId = "00000000-0000-0000-0000-000000000000"

        // Act & Assert - Should throw 404 exception
        val hostApiHandler = createHostApiHandler() // Will fail - doesn't exist
        
        try {
            val response = hostApiHandler.disconnectClient(nonExistentClientId)
            fail("Should have thrown exception for non-existent client")
        } catch (e: NotFoundException) {
            assertEquals("Status code should be 404", 404, e.statusCode)
            assertTrue("Error message should indicate client not found", 
                e.message!!.contains("not found") || e.message!!.contains("Client"))
        }
    }

    @Test
    fun `POST clients disconnect validates client ID format`() = runTest {
        // Arrange - Invalid client ID formats
        val invalidClientIds = listOf("", "invalid-uuid", "123", "not-a-uuid-at-all")

        val hostApiHandler = createHostApiHandler() // Will fail - doesn't exist

        // Act & Assert - Each invalid ID should fail validation
        invalidClientIds.forEach { invalidId ->
            try {
                val response = hostApiHandler.disconnectClient(invalidId)
                fail("Should have rejected invalid client ID: $invalidId")
            } catch (e: BadRequestException) {
                assertEquals("Status code should be 400", 400, e.statusCode)
                assertTrue("Error message should indicate invalid ID", 
                    e.message!!.contains("Invalid") || e.message!!.contains("client"))
            }
        }
    }
}
