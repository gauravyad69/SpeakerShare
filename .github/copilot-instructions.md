# GitHub Copilot Instructions - SpeakerShare Android App

## Project Overview
Real-time audio broadcasting Android app with Host/Client modes. Host broadcasts microphone or system audio to multiple clients over LAN without internet requirement.

## Current Feature: Real-Time Audio Broadcasting
**Branch**: `001-build-an-android`  
**Phase**: Design Complete (Phase 1)  
**Next Phase**: Task generation and implementation

## Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose  
- **Audio**: WebRTC (primary), UDP+AAC (fallback)
- **Network**: Ktor for HTTP, Android NsdManager for discovery
- **Async**: Kotlin Coroutines
- **Architecture**: MVVM with Repository pattern

## Key Dependencies
```kotlin
// WebRTC
implementation "io.getstream:stream-webrtc-android-compose:$version"

// Ktor
implementation "io.ktor:ktor-client-android:$ktor_version"
implementation "io.ktor:ktor-server-core:$ktor_version"

// Compose
implementation "androidx.compose.ui:ui:$compose_version"
implementation "androidx.activity:activity-compose:$activity_version"
```

## Architecture Patterns
- **MVVM**: ViewModels for UI state management
- **Repository Pattern**: Data layer abstraction
- **Clean Architecture**: Separation of concerns
- **Dependency Injection**: Hilt for DI

## Core Components
1. **AudioStreamManager**: Handles WebRTC/UDP streaming
2. **HostService**: Manages broadcasting and client connections  
3. **ClientManager**: Handles client-side connection and playback
4. **NetworkDiscovery**: mDNS/UDP broadcast discovery
5. **AudioCapture**: Microphone/system audio capture
6. **UI Screens**: Host, Client, Settings, Connection management

## Data Models
- `HostSession`: Broadcasting session state
- `ClientConnection`: Client connection info
- `AudioStream`: Stream configuration and metrics
- `UserSettings`: App preferences

## Key Requirements
- Audio latency < 200ms
- Unlimited concurrent clients (device-limited)
- Default 128kbps AAC quality (tunable)
- LAN-only operation
- Background streaming support
- Android permissions handling

## Network Architecture
- **HTTP API**: Port 8080 (discovery/control)
- **WebRTC**: Dynamic ports with STUN/TURN
- **UDP Fallback**: Port 9090 (audio), 9089 (discovery)
- **Discovery**: mDNS + UDP broadcast

## Recent Changes
- Feature specification completed
- Implementation plan finalized  
- Data model designed
- API contracts defined
- Quickstart guide created

## Implementation Priority
1. Core audio streaming (WebRTC first)
2. Basic UI (Host/Client selection)
3. Network discovery and connection
4. Audio controls (mute, volume, source switching)
5. Client management (kick, monitoring)
6. UDP fallback implementation
7. Permission handling and error recovery

## Code Style
- Kotlin coding conventions
- Compose best practices
- Coroutines for async operations
- Repository pattern for data access
- Sealed classes for state management

---
*Auto-updated by .specify system | Keep manual additions between markers*
<!-- MANUAL_ADDITIONS_START -->
<!-- Add your manual context here -->
<!-- MANUAL_ADDITIONS_END -->
