package io.github.gauravyad69.speakershare.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.zip.CRC32

/**
 * Unit tests for the production UdpPacketHandler wire protocol.
 * These are regression tests for the 28-byte header format:
 *   0-3 magic (0x53504B52), 4 version, 5 type, 6-7 fragment info,
 *   8-15 sessionId, 16-19 sequence, 20-23 timestamp, 24-27 CRC32 (payload only)
 */
class UdpPacketHandlerTest {

    private lateinit var packetHandler: UdpPacketHandler

    @Before
    fun setUp() {
        packetHandler = UdpPacketHandler()
    }

    @Test
    fun `audio packet round trip preserves all header fields`() {
        val sessionId = "testses1" // exactly 8 chars - fits the header field
        val audioData = ByteArray(512) { (it % 251).toByte() }

        val packets = packetHandler.createAudioPacket(
            sessionId = sessionId,
            sequenceNumber = 42L,
            timestamp = 123456789L,
            audioData = audioData
        )

        assertEquals(1, packets.size)

        val parsed = packetHandler.parsePacket(packets[0])
        assertNotNull(parsed)
        parsed!!
        assertEquals(sessionId, parsed.sessionId)
        assertEquals(42L, parsed.sequenceNumber)
        assertEquals(123456789L, parsed.timestamp)
        assertTrue(parsed.isAudioPacket())
        assertTrue(audioData.contentEquals(parsed.payload))
        assertEquals(1, parsed.totalFragments)
        assertTrue(parsed.isLastFragment)
    }

    @Test
    fun `session id is read from byte offset 8 not offset 0`() {
        // Regression test: parsePacket previously mixed absolute and relative
        // ByteBuffer reads, so the sessionId (a relative bulk get at position 0)
        // returned the magic/version/type/fragment bytes instead of the actual
        // session id stored at offset 8.
        val packets = packetHandler.createAudioPacket(
            sessionId = "client1",
            sequenceNumber = 1L,
            timestamp = 100L,
            audioData = byteArrayOf(1, 2, 3)
        )

        val parsed = packetHandler.parsePacket(packets[0])
        assertNotNull(parsed)
        // If the bug were present, sessionId would contain the magic bytes
        assertEquals("client1", parsed!!.sessionId)
    }

    @Test
    fun `session id longer than 8 chars is truncated`() {
        val longSessionId = "client-1234567890-abcdef"
        val packets = packetHandler.createAudioPacket(
            sessionId = longSessionId,
            sequenceNumber = 1L,
            timestamp = 100L,
            audioData = byteArrayOf(9)
        )

        val parsed = packetHandler.parsePacket(packets[0])
        assertNotNull(parsed)
        assertEquals(longSessionId.take(8), parsed!!.sessionId)
    }

    @Test
    fun `control packet round trip preserves command and data`() {
        val controlPacket = packetHandler.createControlPacket(
            sessionId = "sess1234",
            sequenceNumber = 7L,
            command = UdpPacketHandler.CONTROL_KICK
        )

        val parsed = packetHandler.parsePacket(controlPacket)
        assertNotNull(parsed)
        assertTrue(parsed!!.isControlPacket())

        val command = packetHandler.parseControlCommand(parsed)
        assertNotNull(command)
        assertEquals(UdpPacketHandler.CONTROL_KICK, command!!.command)
    }

    @Test
    fun `control packet with payload data round trips`() {
        val data = byteArrayOf(0x0A, 0x0B, 0x0C)
        val controlPacket = packetHandler.createControlPacket(
            sessionId = "sess1234",
            sequenceNumber = 7L,
            command = UdpPacketHandler.CONTROL_TRANSFER_REDIRECT,
            data = data
        )

        val parsed = packetHandler.parsePacket(controlPacket)
        val command = packetHandler.parseControlCommand(parsed!!)
        assertNotNull(command)
        assertTrue(data.contentEquals(command!!.data))
    }

    @Test
    fun `discovery packet round trip preserves host name and port`() {
        val discoveryPacket = packetHandler.createDiscoveryPacket(
            hostName = "Living Room Host",
            port = 8080
        )

        val parsed = packetHandler.parsePacket(discoveryPacket)
        assertNotNull(parsed)
        assertTrue(parsed!!.isDiscoveryPacket())

        val info = packetHandler.parseDiscoveryInfo(parsed)
        assertNotNull(info)
        assertEquals("Living Room Host", info!!.hostName)
        assertEquals(8080, info.port)
    }

    @Test
    fun `heartbeat packet round trip`() {
        val heartbeat = packetHandler.createHeartbeatPacket("sess1234")

        val parsed = packetHandler.parsePacket(heartbeat)
        assertNotNull(parsed)
        assertTrue(parsed!!.isHeartbeatPacket())
        assertEquals("sess1234", parsed.sessionId)
    }

    @Test
    fun `large audio data is fragmented and reassembled`() {
        // MAX_PAYLOAD_SIZE = 1400 - 28 = 1372
        val audioData = ByteArray(3000) { (it * 31 % 256).toByte() }

        val packets = packetHandler.createAudioPacket(
            sessionId = "sess1234",
            sequenceNumber = 99L,
            timestamp = 500L,
            audioData = audioData
        )

        assertEquals(3, packets.size)

        // All fragments share the sequence number
        val fragments = packets.mapNotNull { packetHandler.parsePacket(it) }
        assertEquals(3, fragments.size)
        fragments.forEach { assertEquals(99L, it.sequenceNumber) }
        assertEquals(listOf(0, 1, 2), fragments.map { it.fragmentIndex })
        fragments.forEach { assertEquals(3, it.totalFragments) }

        // Reassemble in fragment order and compare with original
        val reassembled = fragments.sortedBy { it.fragmentIndex }
            .flatMap { it.payload.toList() }
            .toByteArray()
        assertTrue(audioData.contentEquals(reassembled))
    }

    @Test
    fun `pcm audio packets use pcm packet type`() {
        val packets = packetHandler.createPcmAudioPacket(
            sessionId = "sess1234",
            sequenceNumber = 1L,
            timestamp = 10L,
            pcmData = ByteArray(256) { it.toByte() }
        )

        val parsed = packetHandler.parsePacket(packets[0])
        assertNotNull(parsed)
        assertTrue(parsed!!.isPcmAudioPacket())
        assertFalse(parsed.isAudioPacket())
    }

    @Test
    fun `packet too small is rejected`() {
        assertNull(packetHandler.parsePacket(ByteArray(10)))
        assertNull(packetHandler.parsePacket(ByteArray(0)))
    }

    @Test
    fun `packet with wrong magic number is rejected`() {
        val packets = packetHandler.createAudioPacket(
            sessionId = "sess1234",
            sequenceNumber = 1L,
            timestamp = 1L,
            audioData = byteArrayOf(1)
        )
        // Corrupt magic bytes
        packets[0][0] = 0x00
        packets[0][1] = 0x00
        packets[0][2] = 0x00
        packets[0][3] = 0x00

        assertNull(packetHandler.parsePacket(packets[0]))
    }

    @Test
    fun `packet with corrupted payload fails CRC validation`() {
        val packets = packetHandler.createAudioPacket(
            sessionId = "sess1234",
            sequenceNumber = 1L,
            timestamp = 1L,
            audioData = ByteArray(100) { 0x55 }
        )
        // Corrupt one payload byte (after the 28-byte header)
        packets[0][28] = (packets[0][28] + 1).toByte()

        assertNull(packetHandler.parsePacket(packets[0]))
    }

    @Test
    fun `packet with corrupted CRC field is rejected`() {
        val packets = packetHandler.createAudioPacket(
            sessionId = "sess1234",
            sequenceNumber = 1L,
            timestamp = 1L,
            audioData = ByteArray(100) { 0x55 }
        )
        // Corrupt the CRC field itself (bytes 24-27)
        packets[0][24] = (packets[0][24] + 1).toByte()

        assertNull(packetHandler.parsePacket(packets[0]))
    }

    @Test
    fun `crc is computed over payload only`() {
        val payload = ByteArray(64) { (it * 7 % 256).toByte() }
        val packets = packetHandler.createAudioPacket(
            sessionId = "sess1234",
            sequenceNumber = 1L,
            timestamp = 1L,
            audioData = payload
        )

        val expected = CRC32().apply { update(payload) }.value.toInt()
        // CRC lives at header bytes 24-27, big-endian
        val crcBytes = packets[0].sliceArray(24..27)
        val actual = ((crcBytes[0].toInt() and 0xFF) shl 24) or
                ((crcBytes[1].toInt() and 0xFF) shl 16) or
                ((crcBytes[2].toInt() and 0xFF) shl 8) or
                (crcBytes[3].toInt() and 0xFF)
        assertEquals(expected, actual)
    }

    @Test
    fun `header layout is 28 bytes as documented`() {
        val packets = packetHandler.createAudioPacket(
            sessionId = "sess1234",
            sequenceNumber = 1L,
            timestamp = 1L,
            audioData = byteArrayOf(1, 2, 3, 4, 5)
        )

        assertEquals(28 + 5, packets[0].size)
    }

    @Test
    fun `sequence number lower 32 bits are preserved`() {
        val bigSequence = 0x0000_0001_1234_5678L
        val packets = packetHandler.createAudioPacket(
            sessionId = "sess1234",
            sequenceNumber = bigSequence,
            timestamp = 1L,
            audioData = byteArrayOf(1)
        )

        val parsed = packetHandler.parsePacket(packets[0])
        assertNotNull(parsed)
        assertEquals(bigSequence and 0xFFFFFFFFL, parsed!!.sequenceNumber)
    }

    @Test
    fun `validatePacket accepts good packet and rejects corrupt one`() {
        val packets = packetHandler.createAudioPacket(
            sessionId = "sess1234",
            sequenceNumber = 1L,
            timestamp = 1L,
            audioData = byteArrayOf(1)
        )
        assertTrue(packetHandler.validatePacket(packets[0]))

        packets[0][28] = 0x7F
        assertFalse(packetHandler.validatePacket(packets[0]))
    }

    @Test
    fun `fragmentation handles exact multiple of max payload`() {
        // 1372 * 2 = 2744 bytes should produce exactly 2 fragments
        val audioData = ByteArray(1372 * 2) { 0x33 }
        val packets = packetHandler.createAudioPacket(
            sessionId = "sess1234",
            sequenceNumber = 1L,
            timestamp = 1L,
            audioData = audioData
        )
        assertEquals(2, packets.size)
    }
}
