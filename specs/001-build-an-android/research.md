# Research: Real-Time Audio Broadcasting Android App

**Generated**: September 17, 2025  
**Status**: Complete  
**Phase**: 0 - Research & Unknowns Resolution

## Research Tasks Completed

### 1. WebRTC vs UDP Audio Streaming
**Decision**: Dual implementation approach with WebRTC as primary, UDP as fallback  
**Rationale**: 
- WebRTC provides excellent audio quality, built-in network adaptation, and P2P efficiency
- UDP fallback ensures compatibility in restrictive network environments
- Stream WebRTC Android library provides Compose-native integration
**Alternatives considered**: 
- Pure WebRTC: Risk of compatibility issues in some network configurations
- Pure UDP: More complex implementation, requires custom network adaptation

### 2. Audio Encoding Strategy
**Decision**: AAC encoding with 128kbps default, tunable quality  
**Rationale**: 
- AAC provides good compression ratio while maintaining audio quality
- Wide device compatibility and hardware acceleration support
- Tunable bitrate allows bandwidth optimization
**Alternatives considered**: 
- MP3: Patent concerns and slightly lower quality
- Opus: Better quality but less universal hardware support on Android

### 3. Network Discovery Mechanism
**Decision**: Combination of mDNS/Bonjour and UDP broadcast  
**Rationale**: 
- mDNS provides reliable service discovery on modern networks
- UDP broadcast ensures fallback for older network configurations
- Android NsdManager provides built-in mDNS support
**Alternatives considered**: 
- Pure UDP broadcast: Less reliable, more network noise
- Manual IP entry: Poor user experience

### 4. Android Audio Capture Strategy
**Decision**: AudioRecord for microphone, MediaProjection API for system audio  
**Rationale**: 
- AudioRecord provides low-latency microphone access
- MediaProjection required for system audio capture (Android 5.0+)
- Both support PCM format needed for encoding pipeline
**Alternatives considered**: 
- MediaRecorder: Higher latency, less control over audio pipeline
- Third-party audio libraries: Unnecessary complexity

### 5. Real-time Communication Architecture
**Decision**: Ktor for HTTP-based control, WebRTC DataChannels for metadata  
**Rationale**: 
- Ktor provides lightweight HTTP server for device discovery and control
- WebRTC DataChannels for low-latency control messages during streaming
- Clear separation between discovery/control and streaming layers
**Alternatives considered**: 
- Pure WebRTC signaling: More complex for simple control operations
- Raw TCP sockets: Reinventing HTTP functionality

### 6. Android Permissions Strategy
**Decision**: Staged permission requests with educational dialogs  
**Rationale**: 
- RECORD_AUDIO, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE required minimum
- MediaProjection requires special system dialog
- Staged approach improves user acceptance rates
**Alternatives considered**: 
- All-at-once requests: Higher rejection rates
- Runtime-only requests: Poor user experience

### 7. Background Operation Strategy
**Decision**: Foreground Service with notification controls  
**Rationale**: 
- Android requires Foreground Service for background audio processing
- Notification provides quick controls and status visibility
- Prevents system from killing audio streaming process
**Alternatives considered**: 
- Background Service: Restricted by Android power management
- Keep screen on: Battery drain concerns

### 8. Client Connection Management
**Decision**: Connection pooling with heartbeat mechanism  
**Rationale**: 
- Heartbeat detects network disconnections quickly
- Connection pooling handles multiple clients efficiently
- Graceful degradation when clients disconnect
**Alternatives considered**: 
- TCP keep-alive only: Slower disconnect detection
- No heartbeat: Poor connection state management

## Technology Decisions Summary

| Component | Technology | Version/Library |
|-----------|------------|-----------------|
| WebRTC | Stream WebRTC Android | `io.getstream:stream-webrtc-android-compose` |
| HTTP Communication | Ktor Client/Server | Latest stable |
| Audio Encoding | AAC | Android MediaCodec |
| Network Discovery | Android NsdManager | Built-in API |
| UI Framework | Jetpack Compose | Latest stable |
| Async Operations | Kotlin Coroutines | Built-in |
| Audio Capture | AudioRecord + MediaProjection | Android APIs |
| Background Processing | Foreground Service | Android API |

## Architecture Patterns

### Audio Pipeline Architecture
```
Microphone/System Audio → PCM Buffer → AAC Encoder → Network Transport
                                    ↓
Network Transport → AAC Decoder → Audio Buffer → AudioTrack → Speaker
```

### Network Architecture
```
Host Device:
- Ktor HTTP Server (discovery/control)
- WebRTC/UDP Audio Server
- Connection Manager

Client Device:
- Network Discovery Client
- WebRTC/UDP Audio Client  
- Audio Playback Manager
```

### Permission Flow
```
App Launch → Basic permissions → Mode Selection → Audio permissions → System Audio (if needed)
```

## Implementation Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|---------|------------|
| WebRTC connection failures | Medium | High | UDP fallback implementation |
| Android audio permission denials | Low | High | Educational UI, graceful degradation |
| Background service termination | Medium | High | Foreground service, user education |
| Network discovery failures | Low | Medium | Manual IP entry fallback |
| Audio encoding latency | Low | Medium | Hardware acceleration, buffer tuning |

## Performance Benchmarks Target

| Metric | Target | Measurement Method |
|--------|--------|--------------------|
| Audio Latency | <200ms | End-to-end timing |
| Connection Time | <3s | Discovery to audio |
| Memory Usage | <100MB | Active streaming |
| Battery Impact | <10%/hour | Background streaming |
| CPU Usage | <15% | Audio processing |

## Next Phase Requirements

All technical unknowns resolved. Ready for Phase 1 design phase:
- Data model definition
- API contract specification  
- Integration test scenarios
- Quickstart documentation
