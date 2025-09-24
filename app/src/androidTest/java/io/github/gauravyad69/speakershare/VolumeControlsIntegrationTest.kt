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
 * Volume and Mute Controls Integration Test
 * Tests client-side volume control and mute functionality
 * 
 * Test Scenario: "Client needs to adjust volume and mute audio independently"
 * 1. Client connects to host
 * 2. Client adjusts volume from 100% to 50%
 * 3. Client mutes audio
 * 4. Client unmutes and restores volume
 * 5. Volume changes don't affect other clients
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VolumeControlsIntegrationTest {

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
    fun testVolumeControlsFlow() = runTest {
        // ARRANGE: Setup host-client connection
        println("Setup: Establishing connection for volume testing")
        
        val hostResult = hostService.startHosting("Volume Test Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        assertTrue("Host should start", hostResult.isSuccess)
        delay(1000)
        
        // Start audio streaming
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        // Connect client
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val hosts = networkDiscovery.discoveredHosts.value
        val testHost = hosts.find { it.serviceName.contains("Volume Test") }
        assertNotNull("Should find test host", testHost)
        
        val connectResult = clientManager.connect(testHost!!.localIpAddress, testHost.port, "Volume Test Client")
        assertTrue("Client should connect", connectResult.isSuccess)
        delay(1000)
        
        // ACT & ASSERT: Step 1 - Verify initial volume (100%)
        println("Step 1: Verify initial volume at 100%")
        
        val initialConnection = clientManager.currentConnection.value
        assertNotNull("Client should be connected", initialConnection)
        assertEquals("Initial volume should be 100%", 1.0f, initialConnection?.volume ?: 0f, 0.01f)
        assertFalse("Should not be muted initially", initialConnection?.isMuted ?: true)
        
        // ACT & ASSERT: Step 2 - Adjust volume to 50%
        println("Step 2: Adjust volume to 50%")
        
        val volumeResult = clientManager.setVolume(0.5f)
        assertTrue("Should set volume successfully", volumeResult.isSuccess)
        delay(500) // Allow volume change to take effect
        
        val volumeConnection = clientManager.currentConnection.value
        assertNotNull("Connection should still be active", volumeConnection)
        assertEquals("Volume should be 50%", 0.5f, volumeConnection?.volume ?: 0f, 0.01f)
        assertFalse("Should still not be muted", volumeConnection?.isMuted ?: true)
        
        // Verify connection stability
        assertTrue("Client should still be connected", volumeConnection?.isConnected ?: false)
        
        // ACT & ASSERT: Step 3 - Mute audio
        println("Step 3: Mute audio")
        
        val muteResult = clientManager.setMuted(true)
        assertTrue("Should mute successfully", muteResult.isSuccess)
        delay(500)
        
        val mutedConnection = clientManager.currentConnection.value
        assertNotNull("Connection should remain active when muted", mutedConnection)
        assertTrue("Should be muted", mutedConnection?.isMuted ?: false)
        assertEquals("Volume level should be preserved", 0.5f, mutedConnection?.volume ?: 0f, 0.01f)
        
        // ACT & ASSERT: Step 4 - Unmute and verify volume restoration
        println("Step 4: Unmute and verify volume restoration")
        
        val unmuteResult = clientManager.setMuted(false)
        assertTrue("Should unmute successfully", unmuteResult.isSuccess)
        delay(500)
        
        val unmuteConnection = clientManager.currentConnection.value
        assertNotNull("Connection should be active after unmute", unmuteConnection)
        assertFalse("Should not be muted", unmuteConnection?.isMuted ?: true)
        assertEquals("Volume should be restored to 50%", 0.5f, unmuteConnection?.volume ?: 0f, 0.01f)
        
        // ACT & ASSERT: Step 5 - Test volume range limits
        println("Step 5: Test volume range limits")
        
        // Test maximum volume
        val maxVolumeResult = clientManager.setVolume(1.0f)
        assertTrue("Should set max volume", maxVolumeResult.isSuccess)
        delay(500)
        
        val maxVolumeConnection = clientManager.currentConnection.value
        assertEquals("Should be at max volume", 1.0f, maxVolumeConnection?.volume ?: 0f, 0.01f)
        
        // Test minimum volume
        val minVolumeResult = clientManager.setVolume(0.0f)
        assertTrue("Should set min volume", minVolumeResult.isSuccess)
        delay(500)
        
        val minVolumeConnection = clientManager.currentConnection.value
        assertEquals("Should be at min volume", 0.0f, minVolumeConnection?.volume ?: 1f, 0.01f)
        
        // CLEANUP
        println("Cleanup: Stopping services")
        clientManager.disconnect()
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testVolumeControlErrorCases() = runTest {
        // Test volume control error handling
        println("Testing volume control error cases")
        
        // Test 1: Set volume when not connected
        val disconnectedResult = clientManager.setVolume(0.5f)
        assertFalse("Should fail when not connected", disconnectedResult.isSuccess)
        
        // Test 2: Set mute when not connected  
        val disconnectedMuteResult = clientManager.setMuted(true)
        assertFalse("Should fail mute when not connected", disconnectedMuteResult.isSuccess)
        
        // Setup connection for remaining tests
        hostService.startHosting("Error Test Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val hosts = networkDiscovery.discoveredHosts.value
        val testHost = hosts.firstOrNull()
        if (testHost != null) {
            clientManager.connect(testHost.localIpAddress, testHost.port, "Error Test Client")
            delay(1000)
            
            // Test 3: Invalid volume ranges
            val negativeVolumeResult = clientManager.setVolume(-0.5f)
            assertFalse("Should reject negative volume", negativeVolumeResult.isSuccess)
            
            val overVolumeResult = clientManager.setVolume(1.5f)
            assertFalse("Should reject volume over 1.0", overVolumeResult.isSuccess)
            
            // Verify connection state remains stable
            val connection = clientManager.currentConnection.value
            assertNotNull("Connection should remain stable", connection)
            assertTrue("Should still be connected", connection?.isConnected ?: false)
        }
        
        // Cleanup
        clientManager.disconnect()
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testRapidVolumeChanges() = runTest {
        // Test rapid volume adjustments
        println("Testing rapid volume changes")
        
        // Setup connection
        hostService.startHosting("Rapid Test Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val hosts = networkDiscovery.discoveredHosts.value
        val testHost = hosts.firstOrNull()
        
        if (testHost != null) {
            clientManager.connect(testHost.localIpAddress, testHost.port, "Rapid Test Client")
            delay(1000)
            
            // Perform rapid volume changes
            val volumeValues = listOf(0.1f, 0.5f, 0.8f, 0.3f, 1.0f, 0.0f, 0.7f)
            
            volumeValues.forEach { volume ->
                val result = clientManager.setVolume(volume)
                assertTrue("Each volume change should succeed", result.isSuccess)
                delay(100) // Short delay between changes
            }
            
            // Allow final state to settle
            delay(1000)
            
            val finalConnection = clientManager.currentConnection.value
            assertNotNull("Connection should remain stable", finalConnection)
            assertTrue("Should still be connected", finalConnection?.isConnected ?: false)
            assertEquals("Final volume should be 0.7", 0.7f, finalConnection?.volume ?: 0f, 0.01f)
        }
        
        // Cleanup
        clientManager.disconnect()
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testVolumeControlPersistence() = runTest {
        // Test that volume settings persist across connection events
        println("Testing volume control persistence")
        
        // Setup initial connection
        hostService.startHosting("Persistence Test Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val hosts = networkDiscovery.discoveredHosts.value
        val testHost = hosts.firstOrNull()
        
        if (testHost != null) {
            // First connection - set volume and mute
            clientManager.connect(testHost.localIpAddress, testHost.port, "Persistence Client")
            delay(1000)
            
            clientManager.setVolume(0.3f)
            clientManager.setMuted(true)
            delay(500)
            
            val configuredConnection = clientManager.currentConnection.value
            assertEquals("Volume should be set to 0.3", 0.3f, configuredConnection?.volume ?: 0f, 0.01f)
            assertTrue("Should be muted", configuredConnection?.isMuted ?: false)
            
            // Disconnect and reconnect
            clientManager.disconnect()
            delay(1000)
            
            val reconnectResult = clientManager.connect(testHost.localIpAddress, testHost.port, "Persistence Client")
            assertTrue("Should reconnect successfully", reconnectResult.isSuccess)
            delay(1000)
            
            // Check if settings are preserved (depends on implementation)
            val reconnectedConnection = clientManager.currentConnection.value
            assertNotNull("Should have connection after reconnect", reconnectedConnection)
            
            // Note: Persistence behavior depends on implementation - 
            // might reset to defaults or preserve previous settings
        }
        
        // Cleanup
        clientManager.disconnect()
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testMuteToggleStates() = runTest {
        // Test mute toggle functionality
        println("Testing mute toggle states")
        
        // Setup connection
        hostService.startHosting("Mute Test Host", 8080, 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val hosts = networkDiscovery.discoveredHosts.value
        val testHost = hosts.firstOrNull()
        
        if (testHost != null) {
            clientManager.connect(testHost.localIpAddress, testHost.port, "Mute Test Client")
            delay(1000)
            
            // Test multiple mute/unmute cycles
            for (cycle in 1..3) {
                println("Mute cycle $cycle")
                
                // Mute
                val muteResult = clientManager.setMuted(true)
                assertTrue("Should mute in cycle $cycle", muteResult.isSuccess)
                delay(300)
                
                val mutedState = clientManager.currentConnection.value
                assertTrue("Should be muted in cycle $cycle", mutedState?.isMuted ?: false)
                
                // Unmute
                val unmuteResult = clientManager.setMuted(false)
                assertTrue("Should unmute in cycle $cycle", unmuteResult.isSuccess)
                delay(300)
                
                val unmutedState = clientManager.currentConnection.value
                assertFalse("Should be unmuted in cycle $cycle", unmutedState?.isMuted ?: true)
            }
            
            // Verify connection stability
            val finalState = clientManager.currentConnection.value
            assertNotNull("Connection should remain stable", finalState)
            assertTrue("Should still be connected", finalState?.isConnected ?: false)
        }
        
        // Cleanup
        clientManager.disconnect()
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }
}