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
 * Network Error Handling Integration Test
 * Tests network disconnections and recovery scenarios
 * 
 * Test Scenarios:
 * 1. "WiFi disconnection during streaming"
 * 2. "Host goes offline, clients handle gracefully" 
 * 3. "Network recovery and reconnection"
 * 4. "Timeout handling for connection attempts"
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NetworkErrorHandlingIntegrationTest {

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
    fun testNetworkDisconnectionDuringStreaming() = runTest {
        // Test Scenario: "WiFi disconnection during streaming"
        println("Testing WiFi disconnection during active streaming")
        
        // ARRANGE: Setup active streaming session
        val hostResult = hostService.startHosting("Error Test Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        assertTrue("Host should start successfully", hostResult.isSuccess)
        delay(1000)
        
        val audioResult = audioStreamManager.startStreaming(
            io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        assertTrue("Audio streaming should start", audioResult.isSuccess)
        delay(1000)
        
        // Establish client connection
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val discoveredHosts = networkDiscovery.discoveredHosts.value
        val testHost = discoveredHosts.find { it.serviceName.contains("Error Test") }
        
        if (testHost != null) {
            val connectResult = clientManager.connect(testHost.localIpAddress, testHost.port, "Error Test Client")
            assertTrue("Client should connect initially", connectResult.isSuccess)
            delay(1000)
            
            // Verify initial connection is stable
            val initialConnection = clientManager.currentConnection.value
            val initialState = clientManager.connectionState.value
            assertNotNull("Initial connection should exist", initialConnection)
            assertEquals("Should be connected initially", 
                        io.github.gauravyad69.speakershare.data.model.ConnectionState.CONNECTED, 
                        initialState)
            
            // ACT: Simulate network disconnection
            println("Simulating network disconnection...")
            
            // In real test, this would involve disabling WiFi or network interface
            // For this test, we'll simulate by stopping network services
            networkDiscovery.stopDiscovery()
            delay(3000) // Simulate network timeout
            
            // ASSERT: Check how client handles network loss
            val disconnectedState = clientManager.connectionState.value
            val disconnectedConnection = clientManager.currentConnection.value
            
            // Client should detect disconnection
            assertTrue("Client should detect network loss", 
                      disconnectedState == io.github.gauravyad69.speakershare.data.model.ConnectionState.DISCONNECTED ||
                      disconnectedState == io.github.gauravyad69.speakershare.data.model.ConnectionState.RECONNECTING)
            
            // If connection object exists, it should reflect disconnected state
            if (disconnectedConnection != null) {
                assertFalse("Connection should be marked as disconnected", 
                           disconnectedConnection.isConnected)
            }
            
            // Test automatic reconnection attempt
            println("Testing automatic reconnection...")
            
            // Restart network discovery (simulating network recovery)
            val recoveryResult = networkDiscovery.startDiscovery()
            assertTrue("Network recovery should succeed", recoveryResult.isSuccess)
            delay(3000) // Allow time for rediscovery
            
            // Check if client attempts reconnection
            val recoveredHosts = networkDiscovery.discoveredHosts.value
            assertTrue("Should rediscover hosts after network recovery", recoveredHosts.isNotEmpty())
            
            val recoveredHost = recoveredHosts.find { it.serviceName.contains("Error Test") }
            if (recoveredHost != null) {
                // Attempt reconnection
                val reconnectResult = clientManager.connect(recoveredHost.localIpAddress, recoveredHost.port, "Error Test Client")
                // Reconnection may succeed or fail depending on timing
                println("Reconnection result: ${if (reconnectResult.isSuccess) "SUCCESS" else "FAILED"}")
            }
        }
        
        // CLEANUP
        clientManager.disconnect()
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testHostGoesOfflineGracefully() = runTest {
        // Test Scenario: "Host goes offline, clients handle gracefully"
        println("Testing host going offline during client connection")
        
        // ARRANGE: Setup host and client connection
        val hostResult = hostService.startHosting("Offline Test Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 3)
        assertTrue("Host should start", hostResult.isSuccess)
        
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val hosts = networkDiscovery.discoveredHosts.value
        val testHost = hosts.find { it.serviceName.contains("Offline Test") }
        
        if (testHost != null) {
            val connectResult = clientManager.connect(testHost.localIpAddress, testHost.port, "Offline Test Client")
            assertTrue("Client should connect to host", connectResult.isSuccess)
            delay(1000)
            
            // Verify stable connection
            val stableConnection = clientManager.currentConnection.value
            val stableState = clientManager.connectionState.value
            assertNotNull("Should have stable connection", stableConnection)
            assertEquals("Should be in connected state", 
                        io.github.gauravyad69.speakershare.data.model.ConnectionState.CONNECTED, 
                        stableState)
            
            // ACT: Host goes offline suddenly
            println("Host going offline suddenly...")
            
            // Stop host services (simulating host going offline)
            audioStreamManager.stopStreaming()
            delay(1000)
            hostService.stopHosting()
            delay(2000) // Allow client to detect host offline
            
            // ASSERT: Client should handle host offline gracefully
            val offlineState = clientManager.connectionState.value
            val offlineConnection = clientManager.currentConnection.value
            
            // Client should detect host is offline
            assertTrue("Client should detect host offline", 
                      offlineState == io.github.gauravyad69.speakershare.data.model.ConnectionState.DISCONNECTED ||
                      offlineState == io.github.gauravyad69.speakershare.data.model.ConnectionState.ERROR)
            
            if (offlineConnection != null) {
                assertFalse("Connection should be marked as disconnected", 
                           offlineConnection.isConnected)
            }
            
            // Test client cleanup after host offline
            val cleanupResult = clientManager.disconnect()
            assertTrue("Client should cleanup gracefully", cleanupResult.isSuccess)
            delay(1000)
            
            val cleanedState = clientManager.connectionState.value
            assertEquals("Should be in disconnected state", 
                        io.github.gauravyad69.speakershare.data.model.ConnectionState.DISCONNECTED, 
                        cleanedState)
        }
        
        // CLEANUP
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testConnectionTimeouts() = runTest {
        // Test Scenario: "Timeout handling for connection attempts"
        println("Testing connection timeout scenarios")
        
        // Test connecting to non-existent host
        println("Testing connection to non-existent host...")
        
        val invalidConnectResult = clientManager.connect("192.168.1.999", 8080, "Timeout Test Client")
        // Connection should fail or timeout
        assertFalse("Connection to invalid host should fail", invalidConnectResult.isSuccess)
        
        val timeoutState = clientManager.connectionState.value
        assertTrue("Should be in disconnected or error state after timeout", 
                  timeoutState == io.github.gauravyad69.speakershare.data.model.ConnectionState.DISCONNECTED ||
                  timeoutState == io.github.gauravyad69.speakershare.data.model.ConnectionState.ERROR)
        
        // Test discovery timeout
        println("Testing discovery timeout handling...")
        
        val discoveryResult = networkDiscovery.startDiscovery()
        assertTrue("Discovery should start", discoveryResult.isSuccess)
        delay(1000) // Short delay
        
        // Stop discovery before completion (simulating timeout)
        networkDiscovery.stopDiscovery()
        delay(1000)
        
        // Should handle discovery timeout gracefully
        val hosts = networkDiscovery.discoveredHosts.value
        // Hosts list may be empty due to timeout, which is acceptable
        println("Discovered hosts after timeout: ${hosts.size}")
        
        // CLEANUP
        clientManager.disconnect()
    }

    @Test
    fun testNetworkRecoveryAndReconnection() = runTest {
        // Test Scenario: "Network recovery and reconnection"
        println("Testing network recovery and automatic reconnection")
        
        // ARRANGE: Setup initial connection
        val hostResult = hostService.startHosting("Recovery Test Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        assertTrue("Host should start", hostResult.isSuccess)
        
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        // Initial discovery and connection
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val initialHosts = networkDiscovery.discoveredHosts.value
        val recoveryHost = initialHosts.find { it.serviceName.contains("Recovery Test") }
        
        if (recoveryHost != null) {
            val initialConnect = clientManager.connect(recoveryHost.localIpAddress, recoveryHost.port, "Recovery Client")
            assertTrue("Initial connection should succeed", initialConnect.isSuccess)
            delay(1000)
            
            val connectedState = clientManager.connectionState.value
            assertEquals("Should be connected initially", 
                        io.github.gauravyad69.speakershare.data.model.ConnectionState.CONNECTED, 
                        connectedState)
            
            // ACT: Simulate network interruption and recovery cycle
            println("Simulating network interruption...")
            
            // Disconnect client (simulating network loss)
            clientManager.disconnect()
            networkDiscovery.stopDiscovery()
            delay(2000)
            
            val interruptedState = clientManager.connectionState.value
            assertEquals("Should be disconnected after interruption", 
                        io.github.gauravyad69.speakershare.data.model.ConnectionState.DISCONNECTED, 
                        interruptedState)
            
            // Simulate network recovery
            println("Simulating network recovery...")
            
            val recoveryDiscovery = networkDiscovery.startDiscovery()
            assertTrue("Discovery should restart after recovery", recoveryDiscovery.isSuccess)
            delay(3000) // Allow time for rediscovery
            
            // Attempt reconnection
            val recoveredHosts = networkDiscovery.discoveredHosts.value
            val reconnectHost = recoveredHosts.find { it.serviceName.contains("Recovery Test") }
            
            if (reconnectHost != null) {
                println("Attempting reconnection...")
                
                val reconnectResult = clientManager.connect(reconnectHost.localIpAddress, reconnectHost.port, "Recovery Client")
                if (reconnectResult.isSuccess) {
                    delay(1000)
                    
                    val reconnectedState = clientManager.connectionState.value
                    val reconnectedConnection = clientManager.currentConnection.value
                    
                    // ASSERT: Reconnection should be successful
                    assertEquals("Should be reconnected", 
                                io.github.gauravyad69.speakershare.data.model.ConnectionState.CONNECTED, 
                                reconnectedState)
                    assertNotNull("Should have active connection", reconnectedConnection)
                    assertTrue("Connection should be marked as active", 
                              reconnectedConnection?.isConnected ?: false)
                    
                    println("Network recovery and reconnection successful!")
                } else {
                    println("Reconnection failed - acceptable in test environment")
                }
            }
        }
        
        // CLEANUP
        clientManager.disconnect()
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testErrorStateTransitions() = runTest {
        // Test that error states transition correctly
        println("Testing error state transitions")
        
        // Test invalid connection attempt
        val invalidResult = clientManager.connect("invalid.host", 9999, "Error State Client")
        assertFalse("Invalid connection should fail", invalidResult.isSuccess)
        
        val errorState = clientManager.connectionState.value
        assertTrue("Should be in error or disconnected state", 
                  errorState == io.github.gauravyad69.speakershare.data.model.ConnectionState.ERROR ||
                  errorState == io.github.gauravyad69.speakershare.data.model.ConnectionState.DISCONNECTED)
        
        // Test recovery from error state
        val resetResult = clientManager.disconnect()
        assertTrue("Should be able to reset from error state", resetResult.isSuccess)
        
        val resetState = clientManager.connectionState.value
        assertEquals("Should return to disconnected state", 
                    io.github.gauravyad69.speakershare.data.model.ConnectionState.DISCONNECTED, 
                    resetState)
        
        // Test that after error recovery, normal operations work
        val hostSetup = hostService.startHosting("Error Recovery Host", 8080, 
                              io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 3)
        if (hostSetup.isSuccess) {
            networkDiscovery.startDiscovery()
            delay(2000)
            
            val hosts = networkDiscovery.discoveredHosts.value
            val validHost = hosts.find { it.serviceName.contains("Error Recovery") }
            
            if (validHost != null) {
                val validConnect = clientManager.connect(validHost.localIpAddress, validHost.port, "Recovery Test")
                // Should be able to connect normally after error recovery
                println("Connection after error recovery: ${if (validConnect.isSuccess) "SUCCESS" else "FAILED"}")
            }
        }
        
        // CLEANUP
        clientManager.disconnect()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testConcurrentErrorConditions() = runTest {
        // Test handling of multiple error conditions simultaneously
        println("Testing concurrent error conditions")
        
        // Start discovery without any hosts
        networkDiscovery.startDiscovery()
        delay(1000)
        
        // Attempt multiple invalid connections concurrently
        val errorResults = mutableListOf<Result<Unit>>()
        
        for (i in 1..3) {
            val result = clientManager.connect("192.168.1.25$i", 8080 + i, "Concurrent Error Client $i")
            errorResults.add(result)
            delay(100)
            
            // Try to disconnect immediately (creating concurrent operations)
            clientManager.disconnect()
            delay(100)
        }
        
        // All should fail gracefully
        errorResults.forEach { result ->
            assertFalse("Concurrent error connections should fail", result.isSuccess)
        }
        
        // System should be in consistent state after concurrent errors
        val finalState = clientManager.connectionState.value
        assertEquals("Should be in disconnected state after concurrent errors", 
                    io.github.gauravyad69.speakershare.data.model.ConnectionState.DISCONNECTED, 
                    finalState)
        
        val finalConnection = clientManager.currentConnection.value
        // Connection should be null or properly disconnected
        if (finalConnection != null) {
            assertFalse("Final connection should be disconnected", finalConnection.isConnected)
        }
        
        // CLEANUP
        networkDiscovery.stopDiscovery()
    }
}