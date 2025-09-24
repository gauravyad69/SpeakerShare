package io.github.gauravyad69.speakershare.network

import org.junit.Test
import org.junit.Assert.*
import io.github.gauravyad69.speakershare.network.udp.UdpPacketHandler
import io.github.gauravyad69.speakershare.network.udp.*
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer

/**
 * Contract test for UDP packet format validation
 * Tests the UDP packet structure and validation logic.
 * 
 * TDD: This test MUST fail initially, then pass after implementation.
 */
class UdpPacketFormatTest {
    
    private val packetHandler = UdpPacketHandler()
    
    @Test
    fun `UDP audio packet - valid format`() = runTest {
        // Given: A valid UDP audio packet structure
        val audioData = ByteArray(1024) { it.toByte() }
        val packet = UdpAudioPacket(
            packetType = UdpPacketType.AUDIO_DATA,
            sequenceNumber = 12345,
            timestamp = System.currentTimeMillis(),
            sessionId = "test-session-123",
            audioData = audioData,
            dataLength = audioData.size
        )
        
        // When: Serializing and validating packet
        val serialized = packetHandler.serializeAudioPacket(packet)
        val isValid = packetHandler.validatePacketFormat(serialized)
        
        // Then: Should be valid
        assertTrue(isValid)
        assertTrue(serialized.size > audioData.size) // Has header
        
        // Verify header structure
        val buffer = ByteBuffer.wrap(serialized)
        assertEquals(UdpPacketType.AUDIO_DATA.ordinal.toByte(), buffer.get())
        assertEquals(12345, buffer.getInt())
        assertEquals(packet.timestamp, buffer.getLong())
    }
    
    @Test
    fun `UDP control packet - valid format`() = runTest {
        // Given: A valid UDP control packet
        val packet = UdpControlPacket(
            packetType = UdpPacketType.CONTROL_COMMAND,
            sequenceNumber = 678,
            timestamp = System.currentTimeMillis(),
            sessionId = "control-session",
            command = UdpControlCommand.MUTE,
            parameters = mapOf("clientId" to "client-123")
        )
        
        // When: Serializing and validating packet
        val serialized = packetHandler.serializeControlPacket(packet)
        val isValid = packetHandler.validatePacketFormat(serialized)
        
        // Then: Should be valid
        assertTrue(isValid)
        
        // Verify packet type in header
        val buffer = ByteBuffer.wrap(serialized)
        assertEquals(UdpPacketType.CONTROL_COMMAND.ordinal.toByte(), buffer.get())
        assertEquals(678, buffer.getInt())
    }
    
    @Test
    fun `UDP discovery packet - valid format`() = runTest {
        // Given: A valid UDP discovery packet
        val packet = UdpDiscoveryPacket(
            packetType = UdpPacketType.DISCOVERY_REQUEST,
            sequenceNumber = 1,
            timestamp = System.currentTimeMillis(),
            hostName = "Test Host",
            port = 8080,
            audioSource = "MICROPHONE",
            maxClients = 50,
            currentClients = 5
        )
        
        // When: Serializing and validating packet
        val serialized = packetHandler.serializeDiscoveryPacket(packet)
        val isValid = packetHandler.validatePacketFormat(serialized)
        
        // Then: Should be valid
        assertTrue(isValid)
        
        // Verify discovery-specific fields
        val buffer = ByteBuffer.wrap(serialized)
        assertEquals(UdpPacketType.DISCOVERY_REQUEST.ordinal.toByte(), buffer.get())
        assertEquals(1, buffer.getInt())
    }
    
    @Test
    fun `UDP packet - invalid header`() = runTest {
        // Given: A packet with corrupted header
        val invalidPacket = ByteArray(10) { 0xFF.toByte() }
        
        // When: Validating corrupted packet
        val isValid = packetHandler.validatePacketFormat(invalidPacket)
        
        // Then: Should be invalid
        assertFalse(isValid)
    }
    
    @Test
    fun `UDP packet - too small`() = runTest {
        // Given: A packet that's too small to contain header
        val tooSmallPacket = ByteArray(5) { it.toByte() }
        
        // When: Validating too small packet
        val isValid = packetHandler.validatePacketFormat(tooSmallPacket)
        
        // Then: Should be invalid
        assertFalse(isValid)
    }
    
    @Test
    fun `UDP packet - invalid sequence number`() = runTest {
        // Given: An audio packet with invalid sequence number
        val audioData = ByteArray(512) { it.toByte() }
        val packet = UdpAudioPacket(
            packetType = UdpPacketType.AUDIO_DATA,
            sequenceNumber = -1, // Invalid
            timestamp = System.currentTimeMillis(),
            sessionId = "test-session",
            audioData = audioData,
            dataLength = audioData.size
        )
        
        // When: Serializing and validating packet
        val serialized = packetHandler.serializeAudioPacket(packet)
        val isValid = packetHandler.validatePacketFormat(serialized)
        
        // Then: Should be invalid due to negative sequence number
        assertFalse(isValid)
    }
    
    @Test
    fun `UDP packet - session ID validation`() = runTest {
        // Given: A packet with empty session ID
        val audioData = ByteArray(256) { it.toByte() }
        val packet = UdpAudioPacket(
            packetType = UdpPacketType.AUDIO_DATA,
            sequenceNumber = 100,
            timestamp = System.currentTimeMillis(),
            sessionId = "", // Empty session ID
            audioData = audioData,
            dataLength = audioData.size
        )
        
        // When: Serializing and validating packet
        val serialized = packetHandler.serializeAudioPacket(packet)
        val isValid = packetHandler.validatePacketFormat(serialized)
        
        // Then: Should be invalid due to empty session ID
        assertFalse(isValid)
    }
}