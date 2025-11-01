# Data Model: Real-Time Audio Broadcasting Android App

**Generated**: September 17, 2025  
**Phase**: 1 - Design  
**Dependencies**: research.md

## Core Entities

### 1. Host Session
**Purpose**: Represents an active audio broadcasting session  
**Lifecycle**: Created when user starts hosting, destroyed when broadcasting stops

```kotlin
data class HostSession(
    val sessionId: String,           // Unique session identifier
    val sessionName: String,         // Display name for this session
    val hostName: String,            // Display name for this host (device)
    val audioSource: AudioSource,    // MICROPHONE or SYSTEM_AUDIO
    val quality: AudioQuality,       // Bitrate and encoding settings
    val isActive: Boolean,           // Currently broadcasting
    val startTime: Long,             // Session start timestamp
    val connectedClients: List<ClientConnection>,
    val networkInfo: NetworkInfo,    // IP, port, discovery info
    val maxClients: Int = 0,         // 0 = unlimited
    val requiresPassword: Boolean = false,
    val password: String? = null
)

enum class AudioSource {
    MICROPHONE,
    SYSTEM_AUDIO,
    LINE_IN,
    BLUETOOTH
}

data class AudioQuality(
    val bitrate: Int = 128,          // kbps, tunable 64-320
    val sampleRate: Int = 44100,     // Hz
    val encoding: AudioEncoding = AudioEncoding.AAC,
    val channels: Int = 2            // 1 = mono, 2 = stereo
)

enum class AudioEncoding { AAC, MP3 }
```

**Implementation Notes**:
- `AudioSource` is an enum type - ensure consistency across service layers
- Services should use `AudioSource.MICROPHONE` not string "MICROPHONE"
- `sessionName` added separately from `hostName` for better UX

**State Transitions**:
- IDLE → STARTING → ACTIVE → STOPPING → IDLE
- Emergency transition: ANY → ERROR → IDLE

**Validation Rules**:
- sessionId must be unique across network
- hostName must be 1-50 characters
- connectedClients.size unlimited (hardware constrained)
- quality.bitrate must be 64-320 kbps

### 2. Client Connection
**Purpose**: Represents a client's connection to host session  
**Lifecycle**: Created on connection request, destroyed on disconnect

```kotlin
data class ClientConnection(
    val clientId: String,            // Unique client identifier
    val clientName: String,          // Display name
    val ipAddress: String,           // Client IP address
    val connectionTime: Long,        // When connected
    val status: ConnectionStatus,    // Current connection state
    val audioSettings: ClientAudioSettings,
    val networkMetrics: NetworkMetrics
)

enum class ConnectionStatus {
    CONNECTING,
    CONNECTED, 
    DISCONNECTED,
    KICKED,
    ERROR
}

data class ClientAudioSettings(
    val volume: Float = 1.0f,        // 0.0 - 1.0
    val isMuted: Boolean = false
)

data class NetworkMetrics(
    val latency: Long,               // ms
    val packetLoss: Float,           // percentage
    val bandwidth: Long              // bytes/sec
)
```

**State Transitions**:
- CONNECTING → CONNECTED → DISCONNECTED
- CONNECTING → ERROR
- CONNECTED → KICKED → DISCONNECTED

**Validation Rules**:
- clientId must be unique within session
- clientName must be 1-30 characters
- volume must be 0.0-1.0 range
- Only host can change status to KICKED

### 3. Audio Stream
**Purpose**: Represents real-time audio data flow  
**Lifecycle**: Exists during active session, recreated on audio source changes

```kotlin
data class AudioStream(
    val streamId: String,
    val sessionId: String,           // Parent session
    val source: AudioSource,
    val transport: StreamTransport,  // WebRTC or UDP
    val quality: AudioQuality,
    val isActive: Boolean,
    val bufferSize: Int,             // Audio buffer size
    val metrics: StreamMetrics
)

enum class StreamTransport {
    WEBRTC,                          // Primary transport
    UDP                              // Fallback transport
}

data class StreamMetrics(
    val bytesTransmitted: Long,
    val packetsLost: Int,
    val averageLatency: Long,        // ms
    val peakLatency: Long,           // ms
    val bufferUnderruns: Int
)
```

**Validation Rules**:
- bufferSize must be power of 2, range 256-8192
- metrics updated every 1000ms
- transport automatically selected based on network conditions

### 4. Network Info
**Purpose**: Network configuration and discovery information  
**Lifecycle**: Created with session, updated on network changes

```kotlin
data class NetworkInfo(
    val localIpAddress: String,
    val port: Int,                   // Host listening port
    val networkInterface: String,    // Wi-Fi interface name
    val isHotspot: Boolean,         // Device is hotspot
    val discoveryMethod: DiscoveryMethod,
    val serviceName: String         // mDNS service name
)

enum class DiscoveryMethod {
    MDNS,                           // Preferred method
    UDP_BROADCAST,                  // Fallback
    MANUAL_IP                       // User-entered IP
}
```

**Validation Rules**:
- port must be in range 8000-65535
- localIpAddress must be valid IPv4
- serviceName format: "speakershare-{sessionId}"

### 5. User Settings
**Purpose**: Persistent user preferences and app configuration  
**Lifecycle**: Persisted in SharedPreferences, loaded on app start

```kotlin
data class UserSettings(
    val displayName: String,
    val defaultAudioSource: AudioSource = MICROPHONE,
    val defaultQuality: AudioQuality = AudioQuality(),
    val autoStartHost: Boolean = false,
    val keepScreenOn: Boolean = false,
    val showNetworkMetrics: Boolean = false,
    val maxClients: Int = 0         // 0 = unlimited
)
```

**Validation Rules**:
- displayName must be 1-50 characters
- maxClients range 0-100 (0 = unlimited)
- All settings have sensible defaults

## Entity Relationships

```
HostSession (1) ←→ (0-N) ClientConnection
HostSession (1) ←→ (1) AudioStream
HostSession (1) ←→ (1) NetworkInfo
AudioStream (1) ←→ (1) StreamMetrics
ClientConnection (1) ←→ (1) ClientAudioSettings
ClientConnection (1) ←→ (1) NetworkMetrics
UserSettings (singleton) ←→ (0-1) HostSession (defaults)
```

## Data Flow Patterns

### Host Side Audio Flow
```
Audio Input → PCM Buffer → Encoder → Network Transport → Clients
     ↓              ↓           ↓
Local Monitor → Audio Metrics → Stream Metrics
```

### Client Side Audio Flow  
```
Network Transport → Decoder → Audio Buffer → Audio Output
        ↓              ↓           ↓
Network Metrics → Stream Metrics → UI Updates
```

### Connection Management Flow
```
Discovery Request → Host Validation → Connection Creation → Stream Assignment
```

### State Synchronization
- Host maintains authoritative state for all connections
- Clients maintain local audio settings only
- Periodic state sync every 5 seconds
- Immediate sync on critical state changes

## Storage Strategy

### Persistent Storage (SharedPreferences)
- UserSettings only
- JSON serialization for complex objects

### In-Memory Storage (App Lifecycle)
- All session data (HostSession, ClientConnection, AudioStream)
- Cached for performance, rebuilt on app restart

### No Database Required
- All data is session-based or user settings
- No historical data persistence needed

## Error Handling Patterns

### Data Validation Errors
- Return Result<T, ValidationError> for all operations
- Clear error messages for user display
- Graceful degradation where possible

### Network State Errors
- Automatic retry with exponential backoff
- Fallback to alternative transport methods
- Clear user feedback on connection issues

### Audio Processing Errors
- Buffer underrun recovery
- Automatic quality adaptation
- Error reporting without stream interruption
