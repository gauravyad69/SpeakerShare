package io.github.gauravyad69.speakershare.network

import org.junit.Test
import org.junit.Assert.*
import io.github.gauravyad69.speakershare.network.api.impl.HostApiHandlerImpl
import io.github.gauravyad69.speakershare.network.api.*
import kotlinx.coroutines.test.runTest

/**
 * Contract test for PUT /host/settings endpoint
 * Tests the API contract for updating host session settings.
 * 
 * TDD: This test MUST fail initially, then pass after implementation.
 */
class HostSettingsContractTest {
    
    private val hostApiHandler = HostApiHandlerImpl()
    
    @Test
    fun `PUT host settings - success response`() = runTest {
        // Given: A valid host settings update request
        val request = HostSettingsRequest(
            hostName = "Updated Host Name",
            audioSource = "MICROPHONE",
            quality = AudioQualityRequest(
                bitrate = 192,
                sampleRate = 48000,
                encoding = "AAC"
            ),
            maxClients = 30
        )
        
        // When: Making settings update request
        val response = hostApiHandler.updateSettings(request)
        
        // Then: Should return success response with updated settings
        assertEquals("Updated Host Name", response.hostName)
        assertEquals("MICROPHONE", response.audioSource)
        assertEquals(192, response.quality.bitrate)
        assertEquals(48000, response.quality.sampleRate)
        assertEquals("AAC", response.quality.encoding)
        assertEquals(30, response.maxClients)
        assertTrue(response.updated)
    }
    
    @Test
    fun `PUT host settings - invalid audio source`() = runTest {
        // Given: A request with invalid audio source
        val request = HostSettingsRequest(
            hostName = "Test Host",
            audioSource = "INVALID_SOURCE",
            quality = AudioQualityRequest(
                bitrate = 128,
                sampleRate = 44100,
                encoding = "AAC"
            ),
            maxClients = 50
        )
        
        // When/Then: Should throw validation exception
        try {
            hostApiHandler.updateSettings(request)
            fail("Expected BadRequestException for invalid audio source")
        } catch (e: BadRequestException) {
            assertEquals(400, e.statusCode)
            assertTrue(e.message.contains("Invalid audio source"))
        }
    }
    
    @Test
    fun `PUT host settings - invalid bitrate`() = runTest {
        // Given: A request with invalid bitrate (too low)
        val request = HostSettingsRequest(
            hostName = "Test Host",
            audioSource = "MICROPHONE",
            quality = AudioQualityRequest(
                bitrate = 32, // Too low
                sampleRate = 44100,
                encoding = "AAC"
            ),
            maxClients = 50
        )
        
        // When/Then: Should throw validation exception
        try {
            hostApiHandler.updateSettings(request)
            fail("Expected BadRequestException for invalid bitrate")
        } catch (e: BadRequestException) {
            assertEquals(400, e.statusCode)
            assertTrue(e.message.contains("Invalid bitrate"))
        }
    }
    
    @Test
    fun `PUT host settings - invalid max clients`() = runTest {
        // Given: A request with invalid max clients (negative)
        val request = HostSettingsRequest(
            hostName = "Test Host",
            audioSource = "MICROPHONE",
            quality = AudioQualityRequest(
                bitrate = 128,
                sampleRate = 44100,
                encoding = "AAC"
            ),
            maxClients = -1
        )
        
        // When/Then: Should throw validation exception
        try {
            hostApiHandler.updateSettings(request)
            fail("Expected BadRequestException for invalid max clients")
        } catch (e: BadRequestException) {
            assertEquals(400, e.statusCode)
            assertTrue(e.message.contains("Invalid max clients"))
        }
    }
    
    @Test
    fun `PUT host settings - empty host name`() = runTest {
        // Given: A request with empty host name
        val request = HostSettingsRequest(
            hostName = "",
            audioSource = "MICROPHONE",
            quality = AudioQualityRequest(
                bitrate = 128,
                sampleRate = 44100,
                encoding = "AAC"
            ),
            maxClients = 50
        )
        
        // When/Then: Should throw validation exception
        try {
            hostApiHandler.updateSettings(request)
            fail("Expected BadRequestException for empty host name")
        } catch (e: BadRequestException) {
            assertEquals(400, e.statusCode)
            assertTrue(e.message.contains("Host name cannot be empty"))
        }
    }
}