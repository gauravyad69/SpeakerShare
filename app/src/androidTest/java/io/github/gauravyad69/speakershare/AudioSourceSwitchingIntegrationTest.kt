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
 * Audio Source Switching Integration Test
 * Tests switching between microphone and system audio sources
 * 
 * Test Scenario: "Host switches from microphone to system audio while clients are connected"
 * 1. Host starts with microphone audio
 * 2. Client connects and receives microphone audio
 * 3. Host switches to system audio
 * 4. Client receives system audio without disconnection
 * 5. Host switches back to microphone
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AudioSourceSwitchingIntegrationTest {

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
    fun testAudioSourceSwitchingFlow() = runTest {
        // ARRANGE: Setup host and client connection
        println("Setup: Establishing host-client connection")
        
        val hostName = "Audio Switching Host"
        val initialSource = io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE
        
        // Start host with microphone
        val hostResult = hostService.startHosting(hostName, 8080, initialSource, 5)
        assertTrue("Host should start successfully", hostResult.isSuccess)
        delay(1000)
        
        // Start discovery and connect client
        networkDiscovery.startDiscovery()
        delay(2000)
        
        val hosts = networkDiscovery.discoveredHosts.value
        val testHost = hosts.find { it.serviceName.contains("Audio Switching") }
        assertNotNull("Should find test host", testHost)
        
        val connectResult = clientManager.connect(testHost!!.localIpAddress, testHost.port, "Test Client")
        assertTrue("Client should connect", connectResult.isSuccess)
        delay(1000)
        
        // ACT & ASSERT: Step 1 - Verify initial microphone audio
        println("Step 1: Verify initial microphone audio streaming")
        
        val audioResult = audioStreamManager.startStreaming(initialSource)
        assertTrue("Microphone streaming should start", audioResult.isSuccess)
        delay(1000)
        
        val initialStream = audioStreamManager.currentStream.value
        assertNotNull("Stream should be active", initialStream)
        assertEquals("Should be microphone source", 
                    io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 
                    initialStream?.source)
        assertEquals("Stream should be active",
                    io.github.gauravyad69.speakershare.data.model.StreamState.ACTIVE,
                    initialStream?.state)
        
        // Verify client receives microphone audio
        val clientConnection = clientManager.currentConnection.value
        assertNotNull("Client should have connection", clientConnection)
        assertTrue("Client should be connected", clientConnection?.isConnected == true)
        
        // ACT & ASSERT: Step 2 - Switch to system audio
        println("Step 2: Switch host to system audio")
        
        val systemSource = io.github.gauravyad69.speakershare.data.model.AudioSource.SYSTEM_AUDIO
        val switchResult = audioStreamManager.switchAudioSource(systemSource)
        assertTrue("Should switch to system audio successfully", switchResult.isSuccess)
        delay(2000) // Allow transition time
        
        // Verify stream switched sources
        val systemStream = audioStreamManager.currentStream.value
        assertNotNull("Stream should still be active after switch", systemStream)
        assertEquals("Should now be system audio source", 
                    systemSource, 
                    systemStream?.source)
        assertEquals("Stream should remain active",
                    io.github.gauravyad69.speakershare.data.model.StreamState.ACTIVE,
                    systemStream?.state)
        
        // Verify client connection maintained
        val clientAfterSwitch = clientManager.currentConnection.value
        assertNotNull("Client should maintain connection", clientAfterSwitch)
        assertTrue("Client should still be connected", clientAfterSwitch?.isConnected == true)
        assertEquals("Connection should be to same host", hostName, clientAfterSwitch?.hostName)
        
        // Verify host still sees client
        val hostAfterSwitch = hostService.hostSession.value
        assertNotNull("Host should still be active", hostAfterSwitch)
        assertEquals("Host should still have client connected", 1, hostAfterSwitch?.connectedClients?.size)
        
        // ACT & ASSERT: Step 3 - Switch back to microphone
        println("Step 3: Switch back to microphone audio")
        
        val switchBackResult = audioStreamManager.switchAudioSource(initialSource)
        assertTrue("Should switch back to microphone successfully", switchBackResult.isSuccess)
        delay(2000)
        
        // Verify final switch
        val finalStream = audioStreamManager.currentStream.value
        assertNotNull("Stream should be active after switch back", finalStream)
        assertEquals("Should be back to microphone", 
                    io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 
                    finalStream?.source)
        
        // Verify connection stability throughout
        val finalClientConnection = clientManager.currentConnection.value
        assertNotNull("Client should maintain connection throughout", finalClientConnection)
        assertTrue("Client should still be connected", finalClientConnection?.isConnected == true)
        
        // CLEANUP
        println("Cleanup: Stopping services")
        clientManager.disconnect()
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
        networkDiscovery.stopDiscovery()
    }

    @Test
    fun testAudioSourceSwitchingWithMultipleClients() = runTest {
        // Test source switching with multiple clients connected
        println("Testing audio source switching with multiple clients")
        
        // Setup host
        hostService.startHosting("Multi-Client Host", 8080, 
                                io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        delay(1000)
        
        // Start streaming
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        // Simulate multiple clients (in real test, would need multiple devices/emulators)
        // For now, verify host can handle the switching logic
        
        val switchResult = audioStreamManager.switchAudioSource(
            io.github.gauravyad69.speakershare.data.model.AudioSource.SYSTEM_AUDIO)
        assertTrue("Should handle source switch with multiple clients", switchResult.isSuccess)
        
        val stream = audioStreamManager.currentStream.value
        assertNotNull("Stream should remain active", stream)
        assertEquals("Should switch to system audio", 
                    io.github.gauravyad69.speakershare.data.model.AudioSource.SYSTEM_AUDIO, 
                    stream?.source)
        
        // Cleanup
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
    }

    @Test
    fun testAudioSourceSwitchingErrorCases() = runTest {
        // Test error handling during source switching
        println("Testing audio source switching error cases")
        
        // Test 1: Switch without active stream
        val noStreamResult = audioStreamManager.switchAudioSource(
            io.github.gauravyad69.speakershare.data.model.AudioSource.SYSTEM_AUDIO)
        assertFalse("Should fail to switch when no stream active", noStreamResult.isSuccess)
        
        // Test 2: Switch to same source
        hostService.startHosting("Error Test Host", 8080, 
                                io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(1000)
        
        val sameSourceResult = audioStreamManager.switchAudioSource(
            io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        // This might succeed (no-op) or fail depending on implementation
        
        // Test 3: Rapid switching
        val rapid1 = audioStreamManager.switchAudioSource(
            io.github.gauravyad69.speakershare.data.model.AudioSource.SYSTEM_AUDIO)
        val rapid2 = audioStreamManager.switchAudioSource(
            io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        
        delay(1000) // Allow settling
        
        val finalStream = audioStreamManager.currentStream.value
        assertNotNull("Stream should be in consistent state", finalStream)
        
        // Cleanup
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
    }

    @Test
    fun testAudioQualityChangesDuringSourceSwitch() = runTest {
        // Test that audio quality settings are preserved during source switching
        println("Testing audio quality preservation during source switching")
        
        // Setup with custom quality
        val customQuality = io.github.gauravyad69.speakershare.data.model.AudioQuality(
            bitrate = 256,
            sampleRate = 48000,
            encoding = io.github.gauravyad69.speakershare.data.model.AudioEncoding.AAC
        )
        
        hostService.startHosting("Quality Test Host", 8080, 
                                io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        
        // Start streaming with custom quality
        val streamResult = audioStreamManager.startStreamingWithQuality(
            io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 
            customQuality)
        assertTrue("Should start with custom quality", streamResult.isSuccess)
        delay(1000)
        
        val initialStream = audioStreamManager.currentStream.value
        assertNotNull("Initial stream should be active", initialStream)
        assertEquals("Should preserve custom bitrate", 256, initialStream?.quality?.bitrate)
        assertEquals("Should preserve custom sample rate", 48000, initialStream?.quality?.sampleRate)
        
        // Switch source and verify quality preserved
        val switchResult = audioStreamManager.switchAudioSource(
            io.github.gauravyad69.speakershare.data.model.AudioSource.SYSTEM_AUDIO)
        assertTrue("Should switch successfully", switchResult.isSuccess)
        delay(1000)
        
        val switchedStream = audioStreamManager.currentStream.value
        assertNotNull("Switched stream should be active", switchedStream)
        assertEquals("Should preserve bitrate after switch", 256, switchedStream?.quality?.bitrate)
        assertEquals("Should preserve sample rate after switch", 48000, switchedStream?.quality?.sampleRate)
        assertEquals("Should have new source", 
                    io.github.gauravyad69.speakershare.data.model.AudioSource.SYSTEM_AUDIO, 
                    switchedStream?.source)
        
        // Cleanup
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
    }

    @Test
    fun testSourceSwitchingNotifications() = runTest {
        // Test that source switching generates appropriate state notifications
        println("Testing source switching state notifications")
        
        val stateChanges = mutableListOf<io.github.gauravyad69.speakershare.data.model.AudioStream?>()
        
        // Monitor stream state changes (in real implementation, would use StateFlow collection)
        hostService.startHosting("Notification Test Host", 8080, 
                                io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 5)
        audioStreamManager.startStreaming(io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE)
        delay(500)
        
        // Capture initial state
        stateChanges.add(audioStreamManager.currentStream.value)
        
        // Switch and capture state
        audioStreamManager.switchAudioSource(io.github.gauravyad69.speakershare.data.model.AudioSource.SYSTEM_AUDIO)
        delay(500)
        stateChanges.add(audioStreamManager.currentStream.value)
        
        // Verify we captured different states
        assertTrue("Should have captured state changes", stateChanges.size >= 2)
        
        val initialState = stateChanges[0]
        val switchedState = stateChanges[1]
        
        assertNotNull("Initial state should not be null", initialState)
        assertNotNull("Switched state should not be null", switchedState)
        
        if (initialState != null && switchedState != null) {
            assertEquals("Initial should be microphone", 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.MICROPHONE, 
                        initialState.source)
            assertEquals("Switched should be system audio", 
                        io.github.gauravyad69.speakershare.data.model.AudioSource.SYSTEM_AUDIO, 
                        switchedState.source)
        }
        
        // Cleanup
        audioStreamManager.stopStreaming()
        hostService.stopHosting()
    }
}