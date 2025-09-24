package io.github.gauravyad69.speakershare.network

import org.junit.Test
import org.junit.Assert.*
import io.github.gauravyad69.speakershare.network.udp.UdpHandler
import io.github.gauravyad69.speakershare.network.udp.*
import kotlinx.coroutines.test.runTest

/**
 * Contract test for UDP control commands
 * Tests the UDP control command handling and responses.
 * 
 * TDD: This test MUST fail initially, then pass after implementation.
 */
class UdpControlCommandsTest {
    
    private val udpHandler = UdpHandler()
    
    @Test
    fun `UDP control command - MUTE client`() = runTest {
        // Given: A mute control command
        val command = UdpControlPacket(
            packetType = UdpPacketType.CONTROL_COMMAND,
            sequenceNumber = 1,
            timestamp = System.currentTimeMillis(),
            sessionId = "test-session",
            command = UdpControlCommand.MUTE,
            parameters = mapOf("clientId" to "client-123")
        )
        
        // When: Processing mute command
        val response = udpHandler.processControlCommand(command)
        
        // Then: Should return success response
        assertEquals(UdpPacketType.CONTROL_RESPONSE, response.packetType)
        assertEquals(command.sequenceNumber, response.sequenceNumber)
        assertEquals("SUCCESS", response.status)
        assertEquals("Client muted successfully", response.message)
        assertEquals("client-123", response.parameters["clientId"])
    }
    
    @Test
    fun `UDP control command - UNMUTE client`() = runTest {
        // Given: An unmute control command
        val command = UdpControlPacket(
            packetType = UdpPacketType.CONTROL_COMMAND,
            sequenceNumber = 2,
            timestamp = System.currentTimeMillis(),
            sessionId = "test-session",
            command = UdpControlCommand.UNMUTE,
            parameters = mapOf("clientId" to "client-456")
        )
        
        // When: Processing unmute command
        val response = udpHandler.processControlCommand(command)
        
        // Then: Should return success response
        assertEquals(UdpPacketType.CONTROL_RESPONSE, response.packetType)
        assertEquals(command.sequenceNumber, response.sequenceNumber)
        assertEquals("SUCCESS", response.status)
        assertEquals("Client unmuted successfully", response.message)
        assertEquals("client-456", response.parameters["clientId"])
    }
    
    @Test
    fun `UDP control command - VOLUME_CHANGE`() = runTest {
        // Given: A volume change control command
        val command = UdpControlPacket(
            packetType = UdpPacketType.CONTROL_COMMAND,
            sequenceNumber = 3,
            timestamp = System.currentTimeMillis(),
            sessionId = "test-session",
            command = UdpControlCommand.VOLUME_CHANGE,
            parameters = mapOf(
                "clientId" to "client-789",
                "volume" to "75"
            )
        )
        
        // When: Processing volume change command
        val response = udpHandler.processControlCommand(command)
        
        // Then: Should return success response
        assertEquals(UdpPacketType.CONTROL_RESPONSE, response.packetType)
        assertEquals(command.sequenceNumber, response.sequenceNumber)
        assertEquals("SUCCESS", response.status)
        assertEquals("Volume changed successfully", response.message)
        assertEquals("client-789", response.parameters["clientId"])
        assertEquals("75", response.parameters["volume"])
    }
    
    @Test
    fun `UDP control command - KICK_CLIENT`() = runTest {
        // Given: A kick client control command
        val command = UdpControlPacket(
            packetType = UdpPacketType.CONTROL_COMMAND,
            sequenceNumber = 4,
            timestamp = System.currentTimeMillis(),
            sessionId = "test-session",
            command = UdpControlCommand.KICK_CLIENT,
            parameters = mapOf(
                "clientId" to "client-bad",
                "reason" to "Disruptive behavior"
            )
        )
        
        // When: Processing kick command
        val response = udpHandler.processControlCommand(command)
        
        // Then: Should return success response
        assertEquals(UdpPacketType.CONTROL_RESPONSE, response.packetType)
        assertEquals(command.sequenceNumber, response.sequenceNumber)
        assertEquals("SUCCESS", response.status)
        assertEquals("Client kicked successfully", response.message)
        assertEquals("client-bad", response.parameters["clientId"])
    }
    
    @Test
    fun `UDP control command - SWITCH_AUDIO_SOURCE`() = runTest {
        // Given: A switch audio source control command
        val command = UdpControlPacket(
            packetType = UdpPacketType.CONTROL_COMMAND,
            sequenceNumber = 5,
            timestamp = System.currentTimeMillis(),
            sessionId = "test-session",
            command = UdpControlCommand.SWITCH_AUDIO_SOURCE,
            parameters = mapOf("audioSource" to "SYSTEM_AUDIO")
        )
        
        // When: Processing switch audio source command
        val response = udpHandler.processControlCommand(command)
        
        // Then: Should return success response
        assertEquals(UdpPacketType.CONTROL_RESPONSE, response.packetType)
        assertEquals(command.sequenceNumber, response.sequenceNumber)
        assertEquals("SUCCESS", response.status)
        assertEquals("Audio source switched successfully", response.message)
        assertEquals("SYSTEM_AUDIO", response.parameters["audioSource"])
    }
    
    @Test
    fun `UDP control command - invalid client ID`() = runTest {
        // Given: A control command with invalid client ID
        val command = UdpControlPacket(
            packetType = UdpPacketType.CONTROL_COMMAND,
            sequenceNumber = 6,
            timestamp = System.currentTimeMillis(),
            sessionId = "test-session",
            command = UdpControlCommand.MUTE,
            parameters = mapOf("clientId" to "")
        )
        
        // When: Processing command with invalid client ID
        val response = udpHandler.processControlCommand(command)
        
        // Then: Should return error response
        assertEquals(UdpPacketType.CONTROL_RESPONSE, response.packetType)
        assertEquals(command.sequenceNumber, response.sequenceNumber)
        assertEquals("ERROR", response.status)
        assertTrue(response.message.contains("Invalid client ID"))
    }
    
    @Test
    fun `UDP control command - client not found`() = runTest {
        // Given: A control command for non-existent client
        val command = UdpControlPacket(
            packetType = UdpPacketType.CONTROL_COMMAND,
            sequenceNumber = 7,
            timestamp = System.currentTimeMillis(),
            sessionId = "test-session",
            command = UdpControlCommand.MUTE,
            parameters = mapOf("clientId" to "non-existent-client")
        )
        
        // When: Processing command for non-existent client
        val response = udpHandler.processControlCommand(command)
        
        // Then: Should return error response
        assertEquals(UdpPacketType.CONTROL_RESPONSE, response.packetType)
        assertEquals(command.sequenceNumber, response.sequenceNumber)
        assertEquals("ERROR", response.status)
        assertTrue(response.message.contains("Client not found"))
    }
    
    @Test
    fun `UDP control command - invalid session`() = runTest {
        // Given: A control command with invalid session ID
        val command = UdpControlPacket(
            packetType = UdpPacketType.CONTROL_COMMAND,
            sequenceNumber = 8,
            timestamp = System.currentTimeMillis(),
            sessionId = "",
            command = UdpControlCommand.MUTE,
            parameters = mapOf("clientId" to "client-123")
        )
        
        // When: Processing command with invalid session
        val response = udpHandler.processControlCommand(command)
        
        // Then: Should return error response
        assertEquals(UdpPacketType.CONTROL_RESPONSE, response.packetType)
        assertEquals(command.sequenceNumber, response.sequenceNumber)
        assertEquals("ERROR", response.status)
        assertTrue(response.message.contains("Invalid session"))
    }
}