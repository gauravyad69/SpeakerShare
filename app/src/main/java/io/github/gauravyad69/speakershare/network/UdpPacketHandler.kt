package io.github.gauravyad69.speakershare.network

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.experimental.and

/**
 * UDP Packet Handler for audio streaming
 * Handles packet encoding/decoding, fragmentation, and validation
 */
@Singleton
class UdpPacketHandler @Inject constructor() {
    companion object {
        private const val TAG = "UdpPacketHandler"
        
        // Packet structure constants
        private const val HEADER_SIZE = 28 // bytes (increased to fit timestamp and CRC)
        private const val MAX_PACKET_SIZE = 1400 // bytes (safe for most MTUs)
        private const val MAX_PAYLOAD_SIZE = MAX_PACKET_SIZE - HEADER_SIZE
        
        // Protocol constants
        private const val PROTOCOL_VERSION = 1
        private const val MAGIC_NUMBER = 0x53504B52 // "SPKR" in hex
        
        // Packet types
        const val PACKET_TYPE_AUDIO = 0x01.toByte()
        const val PACKET_TYPE_CONTROL = 0x02.toByte()
        const val PACKET_TYPE_DISCOVERY = 0x03.toByte()
        const val PACKET_TYPE_HEARTBEAT = 0x04.toByte()
        const val PACKET_TYPE_PCM_AUDIO = 0x05.toByte()  // Raw PCM audio (no codec)
        
        // Control commands
        const val CONTROL_CONNECT = 0x01.toByte()
        const val CONTROL_DISCONNECT = 0x02.toByte()
        const val CONTROL_VOLUME = 0x03.toByte()
        const val CONTROL_MUTE = 0x04.toByte()
        const val CONTROL_ACK = 0x05.toByte()
        const val CONTROL_KICK = 0x06.toByte()  // Host -> Client: "You have been kicked"
        
        // Host transfer commands
        const val CONTROL_TRANSFER_REQUEST = 0x10.toByte()   // Host -> Client: "Want to make you host"
        const val CONTROL_TRANSFER_ACCEPT = 0x11.toByte()    // Client -> Host: "I accept, here's my server info"
        const val CONTROL_TRANSFER_REJECT = 0x12.toByte()    // Client -> Host: "I decline"
        const val CONTROL_TRANSFER_COMPLETE = 0x13.toByte()  // New Host -> All Clients: "I'm the new host, reconnect"
        const val CONTROL_TRANSFER_REDIRECT = 0x14.toByte()  // Old Host -> All Clients: "Connect to new host at IP:port"
    }
    
    /**
     * Create audio packet
     */
    fun createAudioPacket(
        sessionId: String,
        sequenceNumber: Long,
        timestamp: Long,
        audioData: ByteArray,
        isLastFragment: Boolean = true,
        fragmentIndex: Int = 0,
        totalFragments: Int = 1
    ): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        
        if (audioData.size <= MAX_PAYLOAD_SIZE) {
            // Single packet
            val packet = createSinglePacket(
                PACKET_TYPE_AUDIO,
                sessionId,
                sequenceNumber,
                timestamp,
                audioData,
                fragmentIndex,
                totalFragments,
                isLastFragment
            )
            packets.add(packet)
        } else {
            // Fragment into multiple packets
            val fragmentCount = (audioData.size + MAX_PAYLOAD_SIZE - 1) / MAX_PAYLOAD_SIZE
            var offset = 0
            
            for (i in 0 until fragmentCount) {
                val fragmentSize = minOf(MAX_PAYLOAD_SIZE, audioData.size - offset)
                val fragmentData = audioData.copyOfRange(offset, offset + fragmentSize)
                
                val packet = createSinglePacket(
                    PACKET_TYPE_AUDIO,
                    sessionId,
                    sequenceNumber,
                    timestamp,
                    fragmentData,
                    i,
                    fragmentCount,
                    i == fragmentCount - 1
                )
                
                packets.add(packet)
                offset += fragmentSize
            }
        }
        
        return packets
    }
    
    /**
     * Create raw PCM audio packet (no codec, for lowest latency)
     */
    fun createPcmAudioPacket(
        sessionId: String,
        sequenceNumber: Long,
        timestamp: Long,
        pcmData: ByteArray
    ): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        
        if (pcmData.size <= MAX_PAYLOAD_SIZE) {
            val packet = createSinglePacket(
                PACKET_TYPE_PCM_AUDIO,
                sessionId,
                sequenceNumber,
                timestamp,
                pcmData,
                0,
                1,
                true
            )
            packets.add(packet)
        } else {
            // Fragment into multiple packets
            val fragmentCount = (pcmData.size + MAX_PAYLOAD_SIZE - 1) / MAX_PAYLOAD_SIZE
            var offset = 0
            
            for (i in 0 until fragmentCount) {
                val fragmentSize = minOf(MAX_PAYLOAD_SIZE, pcmData.size - offset)
                val fragmentData = pcmData.copyOfRange(offset, offset + fragmentSize)
                
                val packet = createSinglePacket(
                    PACKET_TYPE_PCM_AUDIO,
                    sessionId,
                    sequenceNumber,
                    timestamp,
                    fragmentData,
                    i,
                    fragmentCount,
                    i == fragmentCount - 1
                )
                
                packets.add(packet)
                offset += fragmentSize
            }
        }
        
        return packets
    }
    
    /**
     * Create control packet
     */
    fun createControlPacket(
        sessionId: String,
        sequenceNumber: Long,
        command: Byte,
        data: ByteArray = byteArrayOf()
    ): ByteArray {
        val controlPayload = ByteArray(1 + data.size)
        controlPayload[0] = command
        if (data.isNotEmpty()) {
            System.arraycopy(data, 0, controlPayload, 1, data.size)
        }
        
        return createSinglePacket(
            PACKET_TYPE_CONTROL,
            sessionId,
            sequenceNumber,
            System.currentTimeMillis(),
            controlPayload
        )
    }
    
    /**
     * Create discovery packet
     */
    fun createDiscoveryPacket(hostName: String, port: Int): ByteArray {
        val nameBytes = hostName.toByteArray(Charsets.UTF_8)
        val payloadSize = 4 + nameBytes.size // 4 bytes for port + host name
        val payload = ByteArray(payloadSize)
        
        // Write port (4 bytes)
        ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(port)
        
        // Write host name
        System.arraycopy(nameBytes, 0, payload, 4, nameBytes.size)
        
        return createSinglePacket(
            PACKET_TYPE_DISCOVERY,
            "discovery",
            0L,
            System.currentTimeMillis(),
            payload
        )
    }
    
    /**
     * Create heartbeat packet
     */
    fun createHeartbeatPacket(sessionId: String): ByteArray {
        return createSinglePacket(
            PACKET_TYPE_HEARTBEAT,
            sessionId,
            0L,
            System.currentTimeMillis(),
            byteArrayOf()
        )
    }
    
    /**
     * Create single packet with header
     */
    private fun createSinglePacket(
        packetType: Byte,
        sessionId: String,
        sequenceNumber: Long,
        timestamp: Long,
        payload: ByteArray,
        fragmentIndex: Int = 0,
        totalFragments: Int = 1,
        isLastFragment: Boolean = true
    ): ByteArray {
        val sessionIdBytes = sessionId.take(8).toByteArray(Charsets.UTF_8)
        val paddedSessionId = ByteArray(8)
        System.arraycopy(sessionIdBytes, 0, paddedSessionId, 0, minOf(sessionIdBytes.size, 8))
        
        val packet = ByteArray(HEADER_SIZE + payload.size)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        
        // Header structure:
        // 0-3: Magic number (4 bytes)
        // 4: Protocol version (1 byte)
        // 5: Packet type (1 byte)
        // 6-7: Fragment info (2 bytes: index | total | last flag)
        // 8-15: Session ID (8 bytes)
        // 16-19: Sequence number (4 bytes)
        // 20-23: Timestamp (4 bytes) - using lower 32 bits
        // 24-27: CRC32 checksum (4 bytes) - calculated after header
        
        // Use buffer in sequential mode - position() advances automatically
        buffer.position(0)
        
        // Magic number
        buffer.putInt(MAGIC_NUMBER)
        
        // Protocol version and packet type
        buffer.put(PROTOCOL_VERSION.toByte())
        buffer.put(packetType)
        
        // Fragment info (2 bytes)
        val fragmentInfo = (fragmentIndex and 0x3F) or 
                          ((totalFragments and 0x3F) shl 6) or
                          (if (isLastFragment) 0x8000 else 0)
        buffer.putShort(fragmentInfo.toShort())
        
        // Session ID (8 bytes)
        buffer.put(paddedSessionId)
        
        // Sequence number (4 bytes) - using lower 32 bits
        buffer.putInt((sequenceNumber and 0xFFFFFFFF).toInt())
        
        // Timestamp (4 bytes) - using lower 32 bits
        buffer.putInt((timestamp and 0xFFFFFFFF).toInt())
        
        // Payload
        System.arraycopy(payload, 0, packet, HEADER_SIZE, payload.size)
        
        // Calculate CRC32 for payload only
        val crc32 = CRC32()
        crc32.update(payload)
        
        // Insert CRC32 at the end of header (absolute position)
        buffer.putInt(HEADER_SIZE - 4, crc32.value.toInt())
        
        // Debug logging for first few packets
        if (sequenceNumber <= 3) {
            val hexDump = packet.take(32).joinToString(" ") { String.format("%02X", it) }
            Log.d(TAG, "Created packet seq=$sequenceNumber: magic=${String.format("0x%08X", MAGIC_NUMBER)}, hex=$hexDump...")
        }
        
        return packet
    }
    
    /**
     * Parse packet and extract information
     */
    fun parsePacket(packetData: ByteArray): UdpPacket? {
        return try {
            if (packetData.size < HEADER_SIZE) {
                Log.w(TAG, "Packet too small: ${packetData.size} bytes, need at least $HEADER_SIZE")
                return null
            }
            
            val buffer = ByteBuffer.wrap(packetData).order(ByteOrder.BIG_ENDIAN)
            var position = 0
            
            // Validate magic number
            val magic = buffer.getInt(position)
            if (magic != MAGIC_NUMBER) {
                if (magic != 0x53504B52) { // Only log if not just wrong endian
                    Log.w(TAG, "Invalid magic number: 0x${magic.toString(16)} (expected 0x${MAGIC_NUMBER.toString(16)})")
                }
                return null
            }
            position += 4
            
            // Protocol version
            val version = buffer.get(position)
            if (version != PROTOCOL_VERSION.toByte()) {
                Log.w(TAG, "Unsupported protocol version: $version")
                return null
            }
            position += 1
            
            // Packet type
            val packetType = buffer.get(position)
            position += 1
            
            // Fragment info
            val fragmentInfo = buffer.getShort(position).toInt()
            val fragmentIndex = fragmentInfo and 0x3F
            val totalFragments = (fragmentInfo shr 6) and 0x3F
            val isLastFragment = (fragmentInfo and 0x8000) != 0
            position += 2
            
            // Session ID
            val sessionIdBytes = ByteArray(8)
            buffer.get(sessionIdBytes)
            val sessionId = String(sessionIdBytes, Charsets.UTF_8).trim('\u0000')
            position += 8
            
            // Sequence number
            val sequenceNumber = buffer.getInt(position).toLong() and 0xFFFFFFFF
            position += 4
            
            // Timestamp
            val timestamp = buffer.getInt(position).toLong() and 0xFFFFFFFF
            position += 4
            
            // CRC32
            val expectedCrc = buffer.getInt(position)
            position += 4
            
            // Payload
            val payloadSize = packetData.size - HEADER_SIZE
            val payload = if (payloadSize > 0) {
                ByteArray(payloadSize).apply {
                    System.arraycopy(packetData, HEADER_SIZE, this, 0, payloadSize)
                }
            } else {
                byteArrayOf()
            }
            
            // Validate CRC32
            val crc32 = CRC32()
            crc32.update(payload)
            val actualCrc = crc32.value.toInt()
            
            if (actualCrc != expectedCrc) {
                Log.w(TAG, "CRC32 mismatch. Expected: 0x${expectedCrc.toString(16)}, Actual: 0x${actualCrc.toString(16)}")
                return null
            }
            
            UdpPacket(
                packetType = packetType,
                sessionId = sessionId,
                sequenceNumber = sequenceNumber,
                timestamp = timestamp,
                payload = payload,
                fragmentIndex = fragmentIndex,
                totalFragments = totalFragments,
                isLastFragment = isLastFragment
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse packet", e)
            null
        }
    }
    
    /**
     * Parse control command from packet
     */
    fun parseControlCommand(packet: UdpPacket): ControlCommand? {
        if (packet.packetType != PACKET_TYPE_CONTROL || packet.payload.isEmpty()) {
            return null
        }
        
        val command = packet.payload[0]
        val data = if (packet.payload.size > 1) {
            packet.payload.copyOfRange(1, packet.payload.size)
        } else {
            byteArrayOf()
        }
        
        return ControlCommand(command, data)
    }
    
    /**
     * Parse discovery info from packet
     */
    fun parseDiscoveryInfo(packet: UdpPacket): DiscoveryInfo? {
        if (packet.packetType != PACKET_TYPE_DISCOVERY || packet.payload.size < 4) {
            return null
        }
        
        return try {
            val buffer = ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN)
            val port = buffer.getInt()
            
            val hostName = if (packet.payload.size > 4) {
                String(packet.payload, 4, packet.payload.size - 4, Charsets.UTF_8)
            } else {
                "Unknown Host"
            }
            
            DiscoveryInfo(hostName, port)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse discovery info", e)
            null
        }
    }
    
    /**
     * Validate packet integrity
     */
    fun validatePacket(packetData: ByteArray): Boolean {
        val packet = parsePacket(packetData)
        return packet != null
    }
}

/**
 * UDP Packet data class
 */
data class UdpPacket(
    val packetType: Byte,
    val sessionId: String,
    val sequenceNumber: Long,
    val timestamp: Long,
    val payload: ByteArray,
    val fragmentIndex: Int = 0,
    val totalFragments: Int = 1,
    val isLastFragment: Boolean = true
) {
    fun isAudioPacket(): Boolean = packetType == UdpPacketHandler.PACKET_TYPE_AUDIO
    fun isPcmAudioPacket(): Boolean = packetType == UdpPacketHandler.PACKET_TYPE_PCM_AUDIO
    fun isAnyAudioPacket(): Boolean = isAudioPacket() || isPcmAudioPacket()
    fun isControlPacket(): Boolean = packetType == UdpPacketHandler.PACKET_TYPE_CONTROL
    fun isDiscoveryPacket(): Boolean = packetType == UdpPacketHandler.PACKET_TYPE_DISCOVERY
    fun isHeartbeatPacket(): Boolean = packetType == UdpPacketHandler.PACKET_TYPE_HEARTBEAT
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UdpPacket

        if (packetType != other.packetType) return false
        if (sessionId != other.sessionId) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (timestamp != other.timestamp) return false
        if (!payload.contentEquals(other.payload)) return false
        if (fragmentIndex != other.fragmentIndex) return false
        if (totalFragments != other.totalFragments) return false
        if (isLastFragment != other.isLastFragment) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packetType.toInt()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + fragmentIndex
        result = 31 * result + totalFragments
        result = 31 * result + isLastFragment.hashCode()
        return result
    }
}

/**
 * Control command data class
 */
data class ControlCommand(
    val command: Byte,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ControlCommand

        if (command != other.command) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = command.toInt()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Discovery info data class
 */
data class DiscoveryInfo(
    val hostName: String,
    val port: Int
)