# UDP Audio Streaming Protocol Contract

## Overview
UDP-based audio streaming protocol for fallback when WebRTC is unavailable. Uses custom packet format with AAC audio encoding.

## Network Configuration
- **Host Port**: 9090 (audio streaming)
- **Client Port**: Dynamic (OS assigned)
- **Discovery Port**: 9089 (UDP broadcast)
- **Protocol**: UDP over IPv4

## Packet Format

### Audio Data Packet
```
| Field          | Size (bytes) | Description                    |
|----------------|--------------|--------------------------------|
| Magic          | 4            | 0x53504B52 ("SPKR")          |
| Version        | 1            | Protocol version (0x01)       |
| Packet Type    | 1            | 0x01 = Audio Data             |
| Session ID     | 16           | UUID bytes                     |
| Sequence       | 4            | Packet sequence number        |
| Timestamp      | 8            | Audio timestamp (microseconds) |
| Payload Size   | 2            | AAC data size (0-1400)        |
| AAC Data       | Variable     | Encoded audio data            |
| Checksum       | 4            | CRC32 of packet               |
```
**Total Header Size**: 40 bytes  
**Maximum Packet Size**: 1440 bytes (fits in standard MTU)

### Control Packet
```
| Field          | Size (bytes) | Description                    |
|----------------|--------------|--------------------------------|
| Magic          | 4            | 0x53504B52 ("SPKR")          |
| Version        | 1            | Protocol version (0x01)       |
| Packet Type    | 1            | 0x02 = Control                |
| Session ID     | 16           | UUID bytes                     |
| Command        | 2            | Control command ID            |
| Payload Size   | 2            | Control data size             |
| Control Data   | Variable     | JSON control message          |
| Checksum       | 4            | CRC32 of packet               |
```

## Control Commands

### 1. Client Join Request (Command: 0x0001)
**Direction**: Client → Host  
**Payload**:
```json
{
  "clientId": "client-uuid",
  "clientName": "Jane's Phone",
  "version": "1.0.0"
}
```

### 2. Join Response (Command: 0x0002)  
**Direction**: Host → Client  
**Payload**:
```json
{
  "status": "ACCEPTED|REJECTED",
  "reason": "OK|MAX_CLIENTS|INVALID_REQUEST",
  "audioConfig": {
    "bitrate": 128,
    "sampleRate": 44100,
    "encoding": "AAC"
  }
}
```

### 3. Heartbeat (Command: 0x0003)
**Direction**: Bidirectional  
**Payload**:
```json
{
  "timestamp": 1726588800000,
  "clientCount": 3
}
```

### 4. Client Disconnect (Command: 0x0004)
**Direction**: Client → Host  
**Payload**:
```json
{
  "clientId": "client-uuid",
  "reason": "USER_DISCONNECT"
}
```

### 5. Client Kick (Command: 0x0005)
**Direction**: Host → Client  
**Payload**:
```json
{
  "clientId": "client-uuid", 
  "reason": "HOST_REMOVED"
}
```

### 6. Audio Config Change (Command: 0x0006)
**Direction**: Host → All Clients  
**Payload**:
```json
{
  "newConfig": {
    "bitrate": 192,
    "sampleRate": 44100,
    "encoding": "AAC"
  },
  "effectiveTime": 1726588900000
}
```

## Discovery Protocol

### Service Announcement (UDP Broadcast)
**Port**: 9089  
**Interval**: Every 5 seconds  
**Payload Format**:
```json
{
  "type": "SPEAKERSHARE_HOST",
  "sessionId": "session-uuid",
  "hostName": "John's Phone",  
  "hostIp": "192.168.1.100",
  "audioPort": 9090,
  "controlPort": 8080,
  "audioConfig": {
    "bitrate": 128,
    "sampleRate": 44100,
    "encoding": "AAC" 
  },
  "clientCount": 2,
  "maxClients": 0,
  "version": "1.0.0"
}
```

### Discovery Request (UDP Broadcast)
**Port**: 9089  
**Payload Format**:
```json
{
  "type": "SPEAKERSHARE_DISCOVERY",
  "clientId": "client-uuid",
  "version": "1.0.0"
}
```

## Audio Streaming Flow

### 1. Connection Establishment
```
1. Client sends UDP broadcast discovery request
2. Host responds with service announcement
3. Client sends Join Request to host control port
4. Host validates and sends Join Response
5. Host begins streaming audio packets to client IP
6. Client acknowledges receipt and begins playback
```

### 2. Streaming Protocol
- **Packet Rate**: ~43 packets/second (for 128kbps AAC)
- **Buffer Management**: 500ms client-side buffer
- **Lost Packet Handling**: Continue playback, no retransmission
- **Sequence Gaps**: Detected and reported in metrics

### 3. Connection Maintenance
- **Heartbeat Interval**: Every 10 seconds
- **Timeout Detection**: 30 seconds without heartbeat
- **Graceful Disconnect**: Control packet before stopping

## Quality of Service

### Adaptive Bitrate
Host can dynamically adjust bitrate based on:
- Network congestion detection
- Client feedback (packet loss reports)
- CPU usage on encoding device

**Bitrate Levels**:
- High: 256 kbps (excellent network)
- Normal: 128 kbps (default)
- Low: 64 kbps (congested network)

### Buffer Management
**Client Buffer Strategy**:
- Target: 500ms audio buffer
- Minimum: 200ms (start playback)
- Maximum: 1000ms (drop old packets)
- Adjustment: ±50ms based on jitter

### Error Recovery
**Packet Loss Handling**:
- Silence insertion for lost audio packets
- Skip corrupted packets (checksum mismatch)
- Report loss statistics to host

**Network Interruption**:
- Client maintains buffer during brief outages
- Automatic reconnection after network recovery
- Host maintains client state for 60 seconds

## Security Considerations

### Network Security
- Local network only (no internet routing)
- No authentication (trusted LAN environment)
- Checksum validation prevents data corruption

### Privacy
- No persistent storage of audio data
- Session data cleared on disconnect
- Client IPs not logged permanently

## Implementation Requirements

### Host Implementation
```kotlin
// UDP server listening on port 9090
// Packet encoding/transmission
// Client connection management
// Control message handling
```

### Client Implementation  
```kotlin
// UDP client receiving on dynamic port
// Packet decoding/audio playback
// Buffer management
// Control message processing
```

### Error Codes
- `0x00`: Success
- `0x01`: Invalid packet format
- `0x02`: Checksum mismatch  
- `0x03`: Unsupported version
- `0x04`: Session not found
- `0x05`: Client limit exceeded
- `0x06`: Invalid command
- `0x07`: Network timeout

## Performance Targets
- **Latency**: <200ms end-to-end
- **Packet Loss Tolerance**: Up to 5% without quality degradation
- **Concurrent Clients**: Limited by network bandwidth
- **CPU Usage**: <10% on modern Android devices
