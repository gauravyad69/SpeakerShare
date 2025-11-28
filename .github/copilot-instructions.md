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
- Initial implementation in progress
- Build errors resolved with architectural learnings documented

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

## Critical Implementation Guidelines

### Kotlin Coroutines
- **Always** use `@OptIn(ExperimentalCoroutinesApi::class)` at file level when using `suspendCancellableCoroutine`
- **Always** implement `invokeOnCancellation` in cancellable coroutines for cleanup
- Use `continuation.resume(value)` not `continuation.resume(value, null)` - the second parameter was removed
- Example:
  ```kotlin
  @file:OptIn(ExperimentalCoroutinesApi::class)
  
  suspend fun createOffer(): SessionDescription {
      return suspendCancellableCoroutine { continuation ->
          // ... async work ...
          continuation.invokeOnCancellation {
              // cleanup
          }
      }
  }
  ```

### WebRTC Integration
- **Always** use fully qualified types: `org.webrtc.IceCandidate`, `org.webrtc.SessionDescription`
- Avoid type conflicts between WebRTC types and custom event types
- Custom events should wrap WebRTC types: `data class IceCandidate(val clientId: String, val candidate: org.webrtc.IceCandidate)`

### Jetpack Compose & Material Design
- **Use Material 3 APIs**: `MaterialTheme.colorScheme.*` not `MaterialTheme.colors.*`
- **Use AutoMirrored icons** for directional elements (supports RTL):
  - `Icons.AutoMirrored.Filled.ArrowBack`
  - `Icons.AutoMirrored.Filled.ArrowForward`
  - `Icons.AutoMirrored.Filled.VolumeUp`
  - `Icons.AutoMirrored.Filled.VolumeOff`
- **Never** use deprecated color APIs in new code

### Data Models & Type Safety
- **Enum Consistency**: Use enum types (`AudioSource.MICROPHONE`) consistently across all layers
- Only convert enums to strings at serialization boundaries (JSON/API)
- Services should accept enum parameters, not strings
- Example:
  ```kotlin
  // CORRECT
  fun initialize(audioSource: AudioSource)
  
  // WRONG
  fun initialize(audioSource: String)
  ```

### Service Architecture
- **All injectable services must define clear interfaces** with standard lifecycle methods:
  - `initialize()` or `init()`
  - `start()` / `stop()` or `startCapture()` / `stopCapture()`
  - `cleanup()` or `release()`
- Document which methods are required vs optional
- Use Hilt `@Inject` consistently for dependency injection

### Package Structure
- **Standardize paths**:
  - Use `services/` not `service/`
  - Use `data/model/` not `data/models/`
  - Be consistent across entire codebase

### Resource Management
- **Create drawable resources early** to prevent cascading build errors
- Required notification icons:
  - `ic_notification`, `ic_stop`, `ic_pause`, `ic_play_arrow`
  - `ic_volume_off`, `ic_volume_up`, `ic_cast_connected`, `ic_cast_disconnected`
  - `ic_error`, `ic_sync`, `ic_search`, `ic_pause_circle`
- Use vector drawables for scalability

### Android Services & Notifications
- Handle API level differences for notification channels (API 26+)
- Use `ServiceCompat.stopForeground()` for compatibility
- Implement proper foreground service lifecycle
- Request appropriate permissions before starting services

### Error Handling
- Always validate enum types before use
- Handle missing methods gracefully with clear error messages
- Log errors with appropriate context (TAG, method name, parameters)
- Provide fallback behavior for non-critical failures

## Common Pitfalls to Avoid
1. ❌ Using `MaterialTheme.colors` instead of `MaterialTheme.colorScheme`
2. ❌ Mixing string and enum types for `AudioSource`, `AudioEncoding`, etc.
3. ❌ Forgetting `@OptIn(ExperimentalCoroutinesApi::class)` for coroutine APIs
4. ❌ Using non-qualified types when WebRTC is involved (e.g., `IceCandidate`)
5. ❌ Missing `invokeOnCancellation` in cancellable coroutines
6. ❌ Inconsistent package paths (`service/` vs `services/`)
7. ❌ Assuming service methods exist without checking implementations
8. ❌ Using regular icons instead of AutoMirrored variants for RTL support

---
*Auto-updated by .specify system | Keep manual additions between markers*
<!-- MANUAL_ADDITIONS_START -->
<!-- MANUAL_ADDITIONS_END -->
