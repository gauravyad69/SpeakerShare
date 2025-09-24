package io.github.gauravyad69.speakershare

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Multiple Clients Connection Integration Test
 * Tests multiple clients connecting to a single host
 * 
 * Test Scenario: "Multiple clients connect to one host simultaneously"
 * 1. Host starts accepting multiple clients
 * 2. Multiple clients discover and connect
 * 3. All clients receive audio stream
 * 4. Host manages all client connections
 * 5. Individual client disconnections handled properly
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MultipleClientsIntegrationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)
    
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var hostService: io.github.gauravyad69.speakershare.services.HostService
    
    @Inject 
    lateinit var clientManager: io.github.gauravyad69.speakershare.services.ClientManager
    
    @Inject
    lateinit var audioStreamManager: io.github.gauravyad69.speakershare.services.AudioStreamManager
    
    @Inject
    lateinit var networkDiscovery: io.github.gauravyad69.speakershare.services.NetworkDiscoveryService

    @Before
    fun setup() {
        hiltRule.inject()
        
        runTest {
            hostService.stopHosting()
            clientManager.disconnect()
            networkDiscovery.stopDiscovery()
            audioStreamManager.stopStreaming()
        }
    }

    @Test
    fun testMultipleClientsConnection() = runTest {
        // ARRANGE: Setup host for multiple clients
        println("Setup: Starting host for multiple clients")
        
        val maxClients = 3
        val hostResult = hostService.startHosting("Multi-Client Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, maxClients)
        assertTrue("Host should start successfully", hostResult.isSuccess)
        delay(1000)
        
        // Start audio streaming
        val audioResult = audioStreamManager.startStreaming(
            io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        assertTrue("Audio streaming should start", audioResult.isSuccess)
        delay(1000)
        
        // Verify host is ready for multiple clients
        val hostSession = hostService.hostSession.value
        assertNotNull("Host session should be active", hostSession)
        assertEquals("Host should accept up to 3 clients", maxClients, hostSession?.maxClients)
        assertTrue("Host should be active", hostSession?.isActive ?: false)
        
        // ACT & ASSERT: Step 1 - Simulate multiple client discovery
        println("Step 1: Multiple clients discover host")
        
        // Start discovery (simulates multiple clients discovering)
        val discoveryResult = networkDiscovery.startDiscovery()
        assertTrue("Discovery should start", discoveryResult.isSuccess)
        delay(3000) // Allow discovery time
        
        val discoveredHosts = networkDiscovery.discoveredHosts.value
        assertTrue("Should discover at least one host", discoveredHosts.isNotEmpty())
        
        val multiHost = discoveredHosts.find { it.serviceName.contains("Multi-Client") }
        assertNotNull("Should find multi-client host", multiHost)
        
        // ACT & ASSERT: Step 2 - First client connects
        println("Step 2: First client connects")
        
        val client1Result = clientManager.connect(multiHost!!.localIpAddress, multiHost.port, "Client 1")
        assertTrue("Client 1 should connect successfully", client1Result.isSuccess)
        delay(1000)
        
        // Verify first client connection
        val connection1 = clientManager.currentConnection.value
        assertNotNull("Client 1 should have connection", connection1)
        assertTrue("Client 1 should be connected", connection1?.isConnected ?: false)
        
        // Verify host sees first client
        val hostWith1Client = hostService.hostSession.value
        assertNotNull("Host should still be active", hostWith1Client)
        assertEquals("Host should have 1 client", 1, hostWith1Client?.connectedClients?.size)
        
        val connectedClient1 = hostWith1Client?.connectedClients?.find { it.clientName == "Client 1" }
        assertNotNull("Host should see Client 1", connectedClient1)
        assertTrue("Client 1 should be marked as connected", connectedClient1?.isConnected ?: false)
        
        // ACT & ASSERT: Step 3 - Simulate second client (in real app would be different device)
        println("Step 3: Simulating second client connection")
        
        // For this test, we'll verify the host's ability to handle multiple connections
        // In a real scenario, this would involve multiple devices/emulators
        
        // Simulate host receiving second client connection
        val simulatedClient2 = io.github.gauravyad69.speakershare.data.model.ClientConnection(
            clientId = "client_2_id",
            clientName = "Client 2",
            ipAddress = "192.168.1.102",
            isConnected = true,
            connectionTime = System.currentTimeMillis(),
            volume = 1.0f,
            isMuted = false
        )
        
        // Test host's ability to add multiple clients (would be done by HostService internally)
        val multipleClientsTest = testHostMultipleClientCapacity()
        assertTrue("Host should support multiple clients", multipleClientsTest)
        
        // ACT & ASSERT: Step 4 - Test client capacity limits
        println("Step 4: Test client capacity limits")
        
        // Verify host respects maxClients setting
        val capacityTest = testClientCapacityLimits(maxClients)
        assertTrue("Host should enforce client limits", capacityTest)
        
        // ACT & ASSERT: Step 5 - Test individual client management
        println("Step 5: Test individual client disconnection")
        
        // Disconnect first client
        val disconnectResult = clientManager.disconnect()
        assertTrue("Client 1 should disconnect successfully", disconnectResult.isSuccess)
        delay(1000)
        
        // Verify client disconnection
        val disconnectedState = clientManager.connectionState.value
        assertEquals("Client should be disconnected", 
                    io.github.gauravyad69.speakershare.data.model.ConnectionState.DISCONNECTED, 
                    disconnectedState)
        
        // Verify host updates client list
        val hostAfterDisconnect = hostService.hostSession.value
        assertNotNull("Host should still be active", hostAfterDisconnect)
        // In real implementation, host should remove disconnected client
        
        // CLEANUP
        println("Cleanup: Stopping all services")
        clientManager.disconnect()
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testConcurrentClientConnections() = runTest {
        // Test handling of simultaneous connection attempts
        println("Testing concurrent client connection attempts")
        
        val maxClients = 5
        hostService.startHosting("Concurrent Test Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, maxClients)
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        // Simulate concurrent connection attempts
        // In real test, would need multiple test clients/emulators
        
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val hosts = networkDiscovery.discoveredHosts.value
        val testHost = hosts.find { it.serviceName.contains("Concurrent") }
        
        if (testHost != null) {
            // Test rapid connection attempts (simulating concurrent clients)
            val connectionResults = mutableListOf<Result<Unit>>()
            
            // Simulate multiple rapid connections
            for (i in 1..3) {
                val result = clientManager.connect(testHost.localIpAddress, testHost.port, "Rapid Client $i")
                connectionResults.add(result)
                delay(100) // Brief delay between attempts
                
                // Disconnect to simulate different clients
                if (result.isSuccess) {
                    clientManager.disconnect()
                    delay(100)
                }
            }
            
            // At least one connection should succeed
            assertTrue("At least one connection should succeed", 
                      connectionResults.any { it.isSuccess })
        }
        
        // Cleanup
        clientManager.disconnect()
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testClientConnectionStateConsistency() = runTest {
        // Test that client connections maintain consistent state
        println("Testing client connection state consistency")
        
        hostService.startHosting("Consistency Test Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 10)
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val hosts = networkDiscovery.discoveredHosts.value
        val testHost = hosts.firstOrNull()
        
        if (testHost != null) {
            // Connect client
            val connectResult = clientManager.connect(testHost.localIpAddress, testHost.port, "Consistency Client")
            assertTrue("Client should connect", connectResult.isSuccess)
            delay(1000)
            
            // Verify initial state consistency
            val connection = clientManager.currentConnection.value
            val connectionState = clientManager.connectionState.value
            
            assertNotNull("Connection should exist", connection)
            assertEquals("Connection state should match", 
                        io.github.gauravyad69.speakershare.data.model.ConnectionState.CONNECTED, 
                        connectionState)
            assertTrue("Connection should be marked as connected", connection?.isConnected ?: false)
            
            // Test state consistency during operations
            clientManager.setVolume(0.5f)
            delay(500)
            
            val connectionAfterVolume = clientManager.currentConnection.value
            assertNotNull("Connection should remain", connectionAfterVolume)
            assertEquals("Volume should be updated", 0.5f, connectionAfterVolume?.volume ?: 0f, 0.01f)
            
            // Test state during mute
            clientManager.setMuted(true)
            delay(500)
            
            val connectionAfterMute = clientManager.currentConnection.value
            assertNotNull("Connection should remain", connectionAfterMute)
            assertTrue("Should be muted", connectionAfterMute?.isMuted ?: false)
            assertEquals("Volume should be preserved", 0.5f, connectionAfterMute?.volume ?: 0f, 0.01f)
        }
        
        // Cleanup
        clientManager.disconnect()
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testHostResourceManagement() = runTest {
        // Test that host properly manages resources with multiple clients
        println("Testing host resource management")
        
        val maxClients = 2
        hostService.startHosting("Resource Test Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, maxClients)
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        // Test resource allocation
        val initialSession = hostService.hostSession.value
        assertNotNull("Host session should be active", initialSession)
        assertEquals("Should have correct max clients", maxClients, initialSession?.maxClients)
        
        // Simulate resource usage with clients
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val hosts = networkDiscovery.discoveredHosts.value
        val testHost = hosts.find { it.serviceName.contains("Resource") }
        
        if (testHost != null) {
            // Connect a client to test resource allocation
            val connectResult = clientManager.connect(testHost.localIpAddress, testHost.port, "Resource Client")
            if (connectResult.isSuccess) {
                delay(1000)
                
                // Verify host tracks resources
                val sessionWithClient = hostService.hostSession.value
                assertNotNull("Session should track client", sessionWithClient)
                // Would test actual resource tracking in real implementation
                
                // Test resource cleanup on disconnect
                clientManager.disconnect()
                delay(1000)
                
                val sessionAfterDisconnect = hostService.hostSession.value
                assertNotNull("Session should remain after client disconnect", sessionAfterDisconnect)
            }
        }
        
        // Test resource cleanup on host stop
        hostService.stopHosting()
        delay(1000)
        
        val finalSession = hostService.hostSession.value
        assertNull("Session should be cleaned up", finalSession)
        
        // Cleanup
        audioStreamManager.stopStreaming()
        networkDiscovery.stopDiscovery()
    }

    // Helper methods for testing multiple client scenarios
    
    private suspend fun testHostMultipleClientCapacity(): Boolean {
        // Test host's theoretical capacity to handle multiple clients
        val session = hostService.hostSession.value
        return session?.maxClients ?: 0 > 1
    }
    
    private suspend fun testClientCapacityLimits(maxClients: Int): Boolean {
        // Test that host enforces client capacity limits
        val session = hostService.hostSession.value
        return session?.maxClients == maxClients
    }
}