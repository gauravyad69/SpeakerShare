# Technical Learnings: SpeakerShare Audio Streaming

**Document Created**: During initial end-to-end testing session  
**Test Devices**: OnePlus CPH2381 (Host) ↔ Samsung SM-E225F (Client)  
**Status**: ✅ Audio streaming working between devices

---

## Overview

This document captures critical technical learnings discovered during the debugging of the real-time audio streaming pipeline. These insights are essential for maintaining and extending the audio functionality.

---

## 1. UDP Packet Format Bug (ByteBuffer Positioning)

### Problem
Packets were being created with incorrect magic number (`0x30353435` instead of `0x53504B52`), causing the client to reject all incoming audio packets.

### Root Cause
Mixed usage of **absolute** and **relative** ByteBuffer operations:

```kotlin
// BROKEN: Mixed absolute/relative positioning
buffer.putInt(position, MAGIC_NUMBER)  // Absolute write at position
position += 4
buffer.put(paddedSessionId)  // Relative write from buffer.position() = 0!
```

The `ByteBuffer.put(byteArray)` method uses the buffer's internal position (which was 0), overwriting the magic number with session ID bytes.

### Solution
Use **sequential relative positioning** throughout:

```kotlin
buffer.position(0)
buffer.putInt(MAGIC_NUMBER)       // Position now at 4
buffer.put(PROTOCOL_VERSION)       // Position now at 5
buffer.put(packetType)             // Position now at 6
buffer.putShort(fragmentInfo)      // Position now at 8
buffer.put(paddedSessionId)        // Position now at 16
buffer.putInt(sequenceNumber)      // etc.
```

### Key Lesson
> **Never mix absolute positioning (`buffer.putInt(position, value)`) with relative array writes (`buffer.put(byteArray)`) in the same buffer operation.**

---

## 2. AAC Decoder CSD-0 (Codec Specific Data) Requirement

### Problem
AAC decoder was receiving encoded audio packets but producing no output. MediaCodec remained silent.

### Root Cause
AAC decoders require **CSD-0** (Codec Specific Data) - also known as **Audio Specific Config (ASC)** - to be provided in the MediaFormat before decoding can begin. This 2-byte header contains essential information for AAC decoding.

### Solution
Generate and provide ASC in MediaFormat:

```kotlin
private fun createAudioSpecificConfig(sampleRate: Int, channelCount: Int): ByteArray {
    // AAC-LC = 2 (audioObjectType)
    val audioObjectType = 2
    
    // Sampling frequency index lookup
    val freqIndex = when (sampleRate) {
        96000 -> 0
        88200 -> 1
        64000 -> 2
        48000 -> 3
        44100 -> 4
        32000 -> 5
        24000 -> 6
        22050 -> 7  // ← We use this
        16000 -> 8
        12000 -> 9
        11025 -> 10
        8000 -> 11
        else -> 4 // Default to 44100Hz
    }
    
    val channelConfig = channelCount.coerceIn(1, 2)
    
    // Build 2-byte ASC
    // Byte 0: audioObjectType (5 bits) + upper 3 bits of freqIndex
    // Byte 1: lower 1 bit of freqIndex + channelConfig (4 bits) + 3 zero bits
    val byte0 = ((audioObjectType shl 3) or (freqIndex shr 1)).toByte()
    val byte1 = (((freqIndex and 1) shl 7) or (channelConfig shl 3)).toByte()
    
    return byteArrayOf(byte0, byte1)
}
```

**For 22050Hz Mono AAC-LC**: CSD-0 = `[0x13, 0x88]`

### MediaFormat Configuration
```kotlin
val mediaFormat = MediaFormat.createAudioFormat(
    MediaFormat.MIMETYPE_AUDIO_AAC, 
    config.sampleRate, 
    config.channelCount
).apply {
    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    setInteger(MediaFormat.KEY_IS_ADTS, 0) // Raw AAC, not ADTS wrapped
    setByteBuffer("csd-0", ByteBuffer.wrap(createAudioSpecificConfig(sampleRate, channelCount)))
}
```

### Key Lesson
> **AAC MediaCodec decoders will silently produce no output without CSD-0. Always generate and provide Audio Specific Config based on sample rate and channel count.**

---

## 3. Sample Rate Matching Requirement

### Problem
After decoder started producing output, audio was garbled/distorted.

### Root Cause
**Sample rate mismatch** between pipeline stages:
- AudioCaptureService: **22050 Hz** (actual capture rate on device)
- AudioEncoder: **22050 Hz** (LOW quality preset)
- AudioDecoder: **22050 Hz** ✅
- AudioTrack: **44100 Hz** ❌ (wrong!)

Playing 22050Hz audio at 44100Hz causes the audio to play at 2x speed with pitch distortion.

### Solution
Ensure **all stages match**:

```kotlin
// ClientManager.startAudioPlayback()
val actualSampleRate = 22050  // Match encoder/decoder

val decoderConfig = AudioDecoder.DecoderConfig(
    sampleRate = actualSampleRate,
    channelCount = 1
)
audioDecoder.startDecoding(decoderConfig)

audioPlaybackService.startPlayback(
    AudioPlaybackService.PlaybackConfig(sampleRate = actualSampleRate)
)
```

### Key Lesson
> **The entire audio pipeline must use consistent sample rates. Any mismatch causes distortion, speed changes, or silence.**

---

## 4. MediaCodec Input Buffer Management

### Problem
After fixing CSD-0, decoder produced only one output frame then stopped.

### Root Cause
In `processInputBuffers()`, when no data was available in the queue, the code was still dequeuing an input buffer and queueing it with zero bytes:

```kotlin
// BROKEN: Queues empty buffers when no data available
val inputBufferIndex = codec.dequeueInputBuffer(0)
if (inputBufferIndex >= 0) {
    val inputPacket = inputPacketQueue.removeFirstOrNull()
    if (inputPacket != null) {
        // Queue data...
    }
    // Missing: else case still dequeued the buffer but didn't return it!
}
```

This confused the MediaCodec state machine.

### Solution
Only dequeue input buffer when data is available:

```kotlin
// Check for data first
val hasData = inputPacketMutex.withLock { inputPacketQueue.isNotEmpty() }
if (!hasData) {
    delay(1)
    return
}

// Now safe to dequeue
val inputBufferIndex = codec.dequeueInputBuffer(5000)
if (inputBufferIndex < 0) return

inputPacketMutex.withLock {
    val inputPacket = inputPacketQueue.removeFirstOrNull()
    if (inputPacket != null) {
        // Process...
        codec.queueInputBuffer(inputBufferIndex, 0, inputPacket.data.size, timestamp, 0)
    }
}
```

### Key Lesson
> **MediaCodec input buffers must always be properly returned (either with data or explicitly released). Never dequeue without a clear plan to queue back.**

---

## 5. Client UDP Audio Architecture

### Problem
Client was trying to connect to host's UDP port, but host sends audio TO clients, not vice versa.

### Root Cause
Misunderstanding of the streaming direction:
- Host captures audio → encodes → sends UDP to all registered client addresses
- Client opens a listening port → receives packets → decodes → plays

### Solution
Client uses `UdpAudioClient.startListening(port)` instead of `connectToHost()`:

```kotlin
// Client registers its listening port with host via HTTP
val request = ClientConnectRequest(
    clientId = ...,
    clientName = ...,
    preferredTransport = "UDP",
    audioPort = CLIENT_AUDIO_PORT  // Port where client listens
)

// Then starts listening on that port
udpAudioClient.startListening(CLIENT_AUDIO_PORT)
```

Host registers client address for broadcasting:
```kotlin
// HostService.handleClientConnection()
val clientAddress = InetAddress.getByName(clientIp)
udpAudioServer.addClient(clientId, clientAddress, clientAudioPort)
```

### Key Lesson
> **In broadcast audio streaming, the client is passive (listens) and the host is active (sends). Client must tell host where to send audio.**

---

## Audio Pipeline Summary

```
┌─────────────────────────────────────────────────────────────────────┐
│                          HOST DEVICE                                 │
├─────────────────────────────────────────────────────────────────────┤
│  AudioCaptureService (22050Hz, Mono, PCM 16-bit)                    │
│           │                                                          │
│           ▼                                                          │
│  AudioEncoder (AAC-LC, 22050Hz, Mono)                               │
│           │                                                          │
│           ▼                                                          │
│  UdpAudioServer                                                      │
│           │ Creates packets: MAGIC(0x53504B52) + Header + AAC data  │
│           ▼                                                          │
│  UDP Send to all registered clients                                  │
└─────────────────────────────────────────────────────────────────────┘
                              │
                    Network (LAN/Hotspot)
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         CLIENT DEVICE                                │
├─────────────────────────────────────────────────────────────────────┤
│  UdpAudioClient (listening on port 9091)                            │
│           │ Receives packets, validates magic, extracts AAC payload │
│           ▼                                                          │
│  AudioDecoder (AAC-LC, CSD-0: 0x13 0x88, 22050Hz)                   │
│           │                                                          │
│           ▼                                                          │
│  AudioPlaybackService (AudioTrack, 22050Hz, Mono)                   │
│           │                                                          │
│           ▼                                                          │
│  Speaker Output                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## UDP Packet Format

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0-3 | 4 | Magic Number | `0x53504B52` ("SPKR") |
| 4 | 1 | Protocol Version | `0x01` |
| 5 | 1 | Packet Type | `0x01`=Audio, `0x02`=Control, etc. |
| 6-7 | 2 | Fragment Info | Fragment index, total, last flag |
| 8-15 | 8 | Session ID | UTF-8 padded to 8 bytes |
| 16-19 | 4 | Sequence Number | Lower 32 bits |
| 20-23 | 4 | Timestamp | Lower 32 bits |
| 24-27 | 4 | CRC32 | Checksum of payload |
| 28+ | Variable | Payload | AAC encoded audio data |

**Total Header Size**: 28 bytes

---

## Configuration Constants

```kotlin
// Audio Capture
const val SAMPLE_RATE = 22050  // Hz
const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

// AAC Encoding
AAC Profile = AAC-LC (Object Type 2)
Bitrate = 64000 bps (LOW quality preset)

// Network
HOST_AUDIO_PORT = 9090
CLIENT_AUDIO_PORT = 9091
DISCOVERY_PORT = 9089
HTTP_API_PORT = 8080

// Protocol
MAGIC_NUMBER = 0x53504B52
HEADER_SIZE = 28 bytes
MAX_PACKET_SIZE = 1400 bytes
```

---

## Testing Checklist

Before declaring audio streaming working:

- [ ] Magic number is `0x53504B52` in first 4 bytes of packet
- [ ] CSD-0 is provided to decoder (`0x13 0x88` for 22050Hz mono)
- [ ] Sample rates match: Capture → Encoder → Decoder → AudioTrack
- [ ] Client registers its listening port with host
- [ ] Host registers client IP+port for UDP broadcasting
- [ ] Decoder only queues input buffer when data is available

---

## Files Modified

1. **UdpPacketHandler.kt** - Fixed ByteBuffer positioning in `createSinglePacket()`
2. **AudioDecoder.kt** - Added CSD-0 generation, fixed input buffer management
3. **AudioPlaybackService.kt** - Added debug logging
4. **ClientManager.kt** - Added AudioDecoder integration, proper sample rate
5. **HostService.kt** - Register clients with UdpAudioServer
6. **HostApiHandler.kt** - Pass client IP and audio port
7. **UdpAudioClient.kt** - Added `startListening()` for passive reception
8. **UdpAudioServer.kt** - Debug logging for broadcast

---

*Last Updated: During initial end-to-end testing*
