package io.github.gauravyad69.speakershare

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Basic Host-Client Connection Integration Test
 * Tests the fundamental connection flow from quickstart.md scenario 1
 * 
 * Test Scenario: "Alice wants to share her phone's audio to Bob's phone"
 * 1. Alice starts as host with microphone audio
 * 2. Bob discovers Alice's phone on network
 * 3. Bob connects as client
 * 4. Audio streams from Alice to Bob
 * 5. Both can see connection status
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BasicConnectionIntegrationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)
    
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    // Injected dependencies that would be provided by Hilt
    @Inject
    lateinit var hostService: io.github.gauravyad69.speakershare.services.HostService
    
    @Inject 
    lateinit var clientManager: io.github.gauravyad69.speakershare.services.ClientManager
    
    @Inject
    lateinit var networkDiscovery: io.github.gauravyad69.speakershare.services.NetworkDiscoveryService
    
    @Inject
    lateinit var audioStreamManager: io.github.gauravyad69.speakershare.services.AudioStreamManager

    @Before
    fun setup() {
        hiltRule.inject()
        
        // Reset services to clean state
        runTest {
            hostService.stopHosting()
            clientManager.disconnect()
            networkDiscovery.stopDiscovery()
        }
    }

    @Test
    fun testBasicHostClientConnection() = runTest {
        // ARRANGE: Setup test environment
        val hostName = "Alice's Phone"
        val hostPort = 8080
        val audioSource = io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE
        
        // ACT & ASSERT: Step 1 - Alice starts hosting
        println("Step 1: Alice starts as host")
        val hostResult = hostService.startHosting(
            hostName = hostName,
            port = hostPort,
            audioSource = audioSource,
            maxClients = 5
        )
        assertTrue("Host should start successfully", hostResult.isSuccess)
        
        // Verify host is active
        delay(1000) // Allow service to initialize
        val hostStatus = hostService.hostSession.value
        assertNotNull("Host session should be active", hostStatus)
        assertEquals("Host should be accepting clients", true, hostStatus?.isActive)
        
        // ACT & ASSERT: Step 2 - Bob starts discovery
        println("Step 2: Bob discovers Alice's phone")
        val discoveryResult = networkDiscovery.startDiscovery()
        assertTrue("Discovery should start successfully", discoveryResult.isSuccess)
        
        // Wait for discovery to find the host
        delay(3000) // Discovery timeout
        val discoveredHosts = networkDiscovery.discoveredHosts.value
        assertTrue("Should discover at least one host", discoveredHosts.isNotEmpty())
        
        val aliceHost = discoveredHosts.find { it.serviceName.contains("Alice") }
        assertNotNull("Should find Alice's host", aliceHost)
        
        // ACT & ASSERT: Step 3 - Bob connects as client
        println("Step 3: Bob connects to Alice")
        requireNotNull(aliceHost)
        val connectionResult = clientManager.connect(
            hostIp = aliceHost.localIpAddress,
            hostPort = aliceHost.port,
            clientName = "Bob's Phone"
        )
        assertTrue("Client should connect successfully", connectionResult.isSuccess)
        
        // Verify connection established
        delay(2000) // Allow connection to establish
        val clientStatus = clientManager.connectionState.value
        assertEquals(
            "Client should be connected", 
            io.github.gauravyad69.speakershare.data.model.ConnectionState.CONNECTED, 
            clientStatus
        )
        
        // Verify host sees the client
        val updatedHostStatus = hostService.hostSession.value
        assertNotNull("Host session should still be active", updatedHostStatus)
        assertEquals("Host should have 1 connected client", 1, updatedHostStatus?.connectedClients?.size)
        
        val bobClient = updatedHostStatus?.connectedClients?.find { it.clientName.contains("Bob") }
        assertNotNull("Host should see Bob's connection", bobClient)
        
        // ACT & ASSERT: Step 4 - Verify audio streaming
        println("Step 4: Verify audio streams from Alice to Bob")
        
        // Start audio streaming on host
        val audioResult = audioStreamManager.startStreaming(audioSource)
        assertTrue("Audio streaming should start", audioResult.isSuccess)
        
        delay(2000) // Allow audio to flow
        
        // Verify audio stream is active
        val audioStatus = audioStreamManager.currentStream.value
        assertNotNull("Audio stream should be active", audioStatus)
        assertEquals(
            "Stream should be active",
            io.github.gauravyad69.speakershare.data.model.StreamState.ACTIVE,
            audioStatus?.state
        )
        
        // ACT & ASSERT: Step 5 - Verify connection status visibility
        println("Step 5: Verify both parties can see connection status")
        
        // Check host perspective
        val hostConnections = hostService.getConnectedClients()
        assertEquals("Host should see 1 client", 1, hostConnections.size)
        assertEquals("Client should be Bob", "Bob's Phone", hostConnections.first().clientName)
        assertTrue("Connection should be active", hostConnections.first().isConnected)
        
        // Check client perspective  
        val clientConnection = clientManager.currentConnection.value
        assertNotNull("Client should have connection info", clientConnection)
        assertEquals("Should be connected to Alice", hostName, clientConnection?.hostName)
        assertTrue("Connection should be active", clientConnection?.isConnected == true)
        
        // CLEANUP
        println("Cleanup: Disconnecting and stopping services")
        clientManager.disconnect()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
        audioStreamManager.stopStreaming()
        
        delay(1000) // Allow cleanup
        
        // Verify cleanup
        assertNull("Host session should be null after stop", hostService.hostSession.value)
        assertEquals(
            "Client should be disconnected",
            io.github.gauravyad69.speakershare.data.model.ConnectionState.DISCONNECTED,
            clientManager.connectionState.value
        )
    }

    @Test
    fun testConnectionFailureScenarios() = runTest {
        // Test 1: Client tries to connect to non-existent host
        println("Test 1: Connect to non-existent host")
        val failResult = clientManager.connect("192.168.1.999", 8080, "Test Client")
        assertFalse("Connection to invalid IP should fail", failResult.isSuccess)
        
        // Test 2: Client tries to connect to host that's not accepting clients
        println("Test 2: Connect when host not accepting clients")
        hostService.startHosting("Test Host", 8080, io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 0) // maxClients = 0
        delay(1000)
        
        val rejectResult = clientManager.connect("127.0.0.1", 8080, "Rejected Client")
        // This might succeed or fail depending on implementation - check the actual behavior
        
        hostService.stopHosting()
    }

    @Test
    fun testNetworkDiscoveryTimeout() = runTest {
        // Test discovery when no hosts are available
        println("Testing discovery timeout with no hosts")
        
        val discoveryResult = networkDiscovery.startDiscovery()
        assertTrue("Discovery should start", discoveryResult.isSuccess)
        
        // Wait for discovery timeout
        delay(12000) // Longer than discovery timeout
        
        val hosts = networkDiscovery.discoveredHosts.value
        // Should be empty or contain only actual network hosts
        
        networkDiscovery.stopDiscovery()
        assertFalse("Discovery should stop", networkDiscovery.isDiscovering.value)
    }

    @Test
    fun testMultipleDiscoveryAttempts() = runTest {
        // Test multiple discovery attempts don't interfere
        println("Testing multiple discovery attempts")
        
        val result1 = networkDiscovery.startDiscovery()
        assertTrue("First discovery should start", result1.isSuccess)
        
        delay(500)
        
        val result2 = networkDiscovery.startDiscovery()
        // Should either succeed (restart) or gracefully handle already running
        
        assertTrue("Discovery should be running", networkDiscovery.isDiscovering.value)
        
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testHostServiceStateConsistency() = runTest {
        // Test host service state remains consistent during operations
        println("Testing host service state consistency")
        
        // Initially should be inactive
        assertNull("Host should start inactive", hostService.hostSession.value)
        
        // Start hosting
        hostService.startHosting("Test Host", 8080, io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        delay(1000)
        
        val session1 = hostService.hostSession.value
        assertNotNull("Session should be active", session1)
        assertTrue("Should be active", session1?.isActive == true)
        
        // Stop hosting
        hostService.stopHosting()
        delay(1000)
        
        assertNull("Session should be null after stop", hostService.hostSession.value)
    }
}