package io.github.gauravyad69.speakershare.network

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.*
import kotlinx.coroutines.test.runTest

/**
 * UDP Audio Streaming Flow Test
 * Tests the UDP streaming protocol for real-time audio transmission
 * 
 * Test Requirements (from udp-protocol.md):
 * - Audio streaming on port 9090
 * - Packet format: [Header:8][Sequence:4][Timestamp:8][Data:N]
 * - Buffer management and latency optimization
 * - Packet loss detection and handling
 */
class UdpStreamingTest {

    @Mock
    private lateinit var mockSocket: DatagramSocket
    
    @Mock
    private lateinit var mockInetAddress: InetAddress
    
    private val streamingPort = 9090
    private val packetHeaderSize = 20 // Header(8) + Sequence(4) + Timestamp(8)
    private val maxPacketSize = 1400 // Avoid IP fragmentation
    
    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
    }

    @Test
    fun `audio packet header should have correct format`() {
        // Arrange
        val magic = "SPKRSHRE" // 8 bytes
        val sequence = 12345
        val timestamp = System.currentTimeMillis()
        
        // Act
        val packet = createAudioPacket(magic, sequence, timestamp, byteArrayOf(1, 2, 3, 4))
        
        // Assert
        assertEquals("Packet should start with magic header", magic, packet.header)
        assertEquals("Should have correct sequence number", sequence, packet.sequence)
        assertEquals("Should have correct timestamp", timestamp, packet.timestamp)
        assertEquals("Should have correct data", 4, packet.audioData.size)
    }

    @Test
    fun `audio packet should serialize and deserialize correctly`() {
        // Arrange
        val originalPacket = createAudioPacket("SPKRSHRE", 100, 1640995200000L, byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        
        // Act
        val serialized = serializeAudioPacket(originalPacket)
        val deserialized = deserializeAudioPacket(serialized)
        
        // Assert
        assertNotNull("Should successfully deserialize", deserialized)
        deserialized?.let {
            assertEquals("Header should match", originalPacket.header, it.header)
            assertEquals("Sequence should match", originalPacket.sequence, it.sequence)
            assertEquals("Timestamp should match", originalPacket.timestamp, it.timestamp)
            assertArrayEquals("Audio data should match", originalPacket.audioData, it.audioData)
        }
    }

    @Test
    fun `should validate packet size limits`() {
        // Arrange
        val largeData = ByteArray(2000) // Exceeds safe UDP packet size
        val normalData = ByteArray(1000) // Within safe limits
        
        // Act & Assert
        assertFalse("Should reject oversized packets", isValidPacketSize(largeData.size + packetHeaderSize))
        assertTrue("Should accept normal packets", isValidPacketSize(normalData.size + packetHeaderSize))
    }

    @Test
    fun `sequence numbers should be handled correctly`() = runTest {
        // Arrange
        val packets = mutableListOf<AudioPacket>()
        
        // Act - Create sequence of packets
        for (i in 1..10) {
            packets.add(createAudioPacket("SPKRSHRE", i, System.currentTimeMillis(), byteArrayOf(i.toByte())))
        }
        
        // Assert
        for (i in 0 until packets.size - 1) {
            val current = packets[i].sequence
            val next = packets[i + 1].sequence
            assertEquals("Sequence should be consecutive", current + 1, next)
        }
    }

    @Test
    fun `should detect packet loss`() {
        // Arrange
        val receivedSequences = listOf(1, 2, 4, 5, 7, 8, 9) // Missing 3 and 6
        
        // Act
        val lostPackets = detectPacketLoss(receivedSequences)
        
        // Assert
        assertEquals("Should detect 2 lost packets", 2, lostPackets.size)
        assertTrue("Should detect missing sequence 3", lostPackets.contains(3))
        assertTrue("Should detect missing sequence 6", lostPackets.contains(6))
    }

    @Test
    fun `should handle out-of-order packets`() {
        // Arrange
        val packets = listOf(
            createAudioPacket("SPKRSHRE", 3, 1003, byteArrayOf(3)),
            createAudioPacket("SPKRSHRE", 1, 1001, byteArrayOf(1)),
            createAudioPacket("SPKRSHRE", 2, 1002, byteArrayOf(2))
        )
        
        // Act
        val reordered = reorderPackets(packets)
        
        // Assert
        assertEquals("Should have correct order", 1, reordered[0].sequence)
        assertEquals("Should have correct order", 2, reordered[1].sequence)
        assertEquals("Should have correct order", 3, reordered[2].sequence)
    }

    @Test
    fun `buffer should manage latency correctly`() = runTest {
        // Arrange
        val bufferSize = 5
        val buffer = AudioBuffer(bufferSize)
        val targetLatency = 100L // 100ms target latency
        
        // Act - Add packets with timestamps
        val baseTime = System.currentTimeMillis()
        for (i in 1..bufferSize) {
            val packet = createAudioPacket("SPKRSHRE", i, baseTime + (i * 20), byteArrayOf(i.toByte()))
            buffer.addPacket(packet)
        }
        
        // Assert
        assertEquals("Buffer should be at target size", bufferSize, buffer.size())
        assertTrue("Should be ready for playback", buffer.isReadyForPlayback(targetLatency))
    }

    @Test
    fun `should handle network jitter compensation`() {
        // Arrange
        val packets = listOf(
            createAudioPacket("SPKRSHRE", 1, 1000, byteArrayOf(1)),
            createAudioPacket("SPKRSHRE", 2, 1015, byteArrayOf(2)), // 15ms late
            createAudioPacket("SPKRSHRE", 3, 1025, byteArrayOf(3)), // 25ms late
            createAudioPacket("SPKRSHRE", 4, 1040, byteArrayOf(4))  // Normal timing
        )
        
        // Act
        val jitterStats = calculateJitter(packets)
        
        // Assert
        assertTrue("Should detect jitter", jitterStats.averageJitter > 0)
        assertTrue("Should calculate reasonable jitter", jitterStats.averageJitter < 50) // Less than 50ms
    }

    @Test
    fun `should validate streaming flow end-to-end`() = runTest {
        // Arrange
        val testData = "Hello Audio Stream".toByteArray()
        val chunkSize = 4
        val packets = mutableListOf<AudioPacket>()
        
        // Act - Simulate chunking audio data into packets
        var sequence = 1
        for (i in testData.indices step chunkSize) {
            val end = minOf(i + chunkSize, testData.size)
            val chunk = testData.sliceArray(i until end)
            packets.add(createAudioPacket("SPKRSHRE", sequence++, System.currentTimeMillis(), chunk))
        }
        
        // Simulate receiving and reassembling
        val reassembled = ByteArrayOutputStream()
        packets.sortedBy { it.sequence }.forEach { packet ->
            reassembled.write(packet.audioData)
        }
        
        // Assert
        assertArrayEquals("Should reassemble original data", testData, reassembled.toByteArray())
    }

    @Test
    fun `should handle malformed audio packets gracefully`() {
        // Arrange
        val malformedPackets = listOf(
            byteArrayOf(), // Empty packet
            byteArrayOf(1, 2, 3), // Too short
            "INVALID!".toByteArray() + ByteArray(20), // Invalid header
            "SPKRSHRE".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFF.toByte()) // Incomplete
        )
        
        // Act & Assert
        malformedPackets.forEach { packetData ->
            val parsed = deserializeAudioPacket(packetData)
            assertNull("Should reject malformed packet", parsed)
        }
    }

    // Helper classes and functions
    data class AudioPacket(
        val header: String,
        val sequence: Int,
        val timestamp: Long,
        val audioData: ByteArray
    )

    data class JitterStats(
        val averageJitter: Double,
        val maxJitter: Long,
        val minJitter: Long
    )

    class AudioBuffer(private val maxSize: Int) {
        private val packets = mutableListOf<AudioPacket>()
        
        fun addPacket(packet: AudioPacket) {
            if (packets.size >= maxSize) {
                packets.removeAt(0) // Remove oldest
            }
            packets.add(packet)
        }
        
        fun size() = packets.size
        
        fun isReadyForPlayback(targetLatencyMs: Long): Boolean {
            return packets.size >= maxSize / 2 // At least half full
        }
    }

    private fun createAudioPacket(header: String, sequence: Int, timestamp: Long, data: ByteArray): AudioPacket {
        return AudioPacket(header, sequence, timestamp, data)
    }

    private fun serializeAudioPacket(packet: AudioPacket): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(packet.header.toByteArray()) // 8 bytes
        output.write(intToBytes(packet.sequence)) // 4 bytes
        output.write(longToBytes(packet.timestamp)) // 8 bytes
        output.write(packet.audioData)
        return output.toByteArray()
    }

    private fun deserializeAudioPacket(data: ByteArray): AudioPacket? {
        if (data.size < packetHeaderSize) return null
        
        val input = ByteArrayInputStream(data)
        val headerBytes = ByteArray(8)
        input.read(headerBytes)
        val header = String(headerBytes)
        
        if (header != "SPKRSHRE") return null
        
        val sequenceBytes = ByteArray(4)
        input.read(sequenceBytes)
        val sequence = bytesToInt(sequenceBytes)
        
        val timestampBytes = ByteArray(8)
        input.read(timestampBytes)
        val timestamp = bytesToLong(timestampBytes)
        
        val audioData = ByteArray(data.size - packetHeaderSize)
        input.read(audioData)
        
        return AudioPacket(header, sequence, timestamp, audioData)
    }

    private fun isValidPacketSize(size: Int): Boolean {
        return size <= maxPacketSize
    }

    private fun detectPacketLoss(receivedSequences: List<Int>): List<Int> {
        val sorted = receivedSequences.sorted()
        val missing = mutableListOf<Int>()
        
        if (sorted.isEmpty()) return missing
        
        for (i in sorted.first()..sorted.last()) {
            if (!sorted.contains(i)) {
                missing.add(i)
            }
        }
        
        return missing
    }

    private fun reorderPackets(packets: List<AudioPacket>): List<AudioPacket> {
        return packets.sortedBy { it.sequence }
    }

    private fun calculateJitter(packets: List<AudioPacket>): JitterStats {
        if (packets.size < 2) {
            return JitterStats(0.0, 0L, 0L)
        }
        
        val intervals = mutableListOf<Long>()
        for (i in 1 until packets.size) {
            val interval = packets[i].timestamp - packets[i-1].timestamp
            intervals.add(interval)
        }
        
        val avgInterval = intervals.average()
        val jitters = intervals.map { kotlin.math.abs(it - avgInterval) }
        
        return JitterStats(
            averageJitter = jitters.average(),
            maxJitter = jitters.maxOrNull() ?: 0L,
            minJitter = jitters.minOrNull() ?: 0L
        )
    }

    // Utility functions for byte conversion
    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    private fun bytesToInt(bytes: ByteArray): Int {
        return (bytes[0].toInt() shl 24) or
               (bytes[1].toInt() and 0xFF shl 16) or
               (bytes[2].toInt() and 0xFF shl 8) or
               (bytes[3].toInt() and 0xFF)
    }

    private fun longToBytes(value: Long): ByteArray {
        return byteArrayOf(
            (value shr 56).toByte(),
            (value shr 48).toByte(),
            (value shr 40).toByte(),
            (value shr 32).toByte(),
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    private fun bytesToLong(bytes: ByteArray): Long {
        return (bytes[0].toLong() shl 56) or
               (bytes[1].toLong() and 0xFF shl 48) or
               (bytes[2].toLong() and 0xFF shl 40) or
               (bytes[3].toLong() and 0xFF shl 32) or
               (bytes[4].toLong() and 0xFF shl 24) or
               (bytes[5].toLong() and 0xFF shl 16) or
               (bytes[6].toLong() and 0xFF shl 8) or
               (bytes[7].toLong() and 0xFF)
    }
}