# Tasks: Real-Time Audio Broadcasting Android App

**Input**: Design documents from `/specs/001-build-an-android/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → Extracted: Kotlin/Android, Compose UI, WebRTC + UDP, Ktor
   → Structure: Single Android module (mobile project type)
2. Load design documents:
   → data-model.md: 5 entities (HostSession, ClientConnection, AudioStream, NetworkInfo, UserSettings)
   → contracts/: 2 files (host-api.md, udp-protocol.md) with 7 endpoints + UDP protocol
   → quickstart.md: 5 test scenarios + edge cases
3. Generate tasks by category:
   → Setup: Android project init, Gradle dependencies, permissions
   → Tests: Contract tests (7), integration tests (5), UDP protocol tests (4)
   → Core: Data models (5), services (6), audio pipeline (5)
   → Integration: Network discovery (3), WebRTC setup (3), UI (8)
   → Polish: Background service (2), permissions (3), unit tests
4. Applied task rules:
   → [P] for different files/independent tasks
   → Sequential for shared components
   → Tests before implementation (TDD)
5. Tasks numbered T001-T054 with clear dependencies
6. Generated parallel execution examples
7. Validated: All contracts tested, all entities modeled, all scenarios covered
8. SUCCESS: Tasks ready for execution
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- File paths relative to Android project root

## Path Conventions
- **Models**: `app/src/main/java/io/github/gauravyad69/speakershare/data/models/`
- **Services**: `app/src/main/java/io/github/gauravyad69/speakershare/services/`
- **UI**: `app/src/main/java/io/github/gauravyad69/speakershare/ui/`
- **Tests**: `app/src/test/java/` (unit), `app/src/androidTest/java/` (instrumentation)

---

## Phase 3.1: Project Setup

- [x] **T001** Initialize Android project with Kotlin and Compose dependencies
  - Create build.gradle.kts with WebRTC, Ktor, Compose BOM dependencies
  - Set minSdkVersion 21, targetSdkVersion 34
  - Enable Compose in buildFeatures

- [x] **T002** Configure Android manifest and permissions
  - Add RECORD_AUDIO, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE permissions
  - Add FOREGROUND_SERVICE permission for background audio
  - Configure network security config for local connections

- [x] **T003** [P] Setup project structure and base packages
  - Create package structure: data/models, services, ui, network, audio
  - Add .gitignore for Android project
  - Setup lint configuration for Kotlin/Compose

---

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3

**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

### Contract Tests (API Endpoints)
- [x] **T004** [P] Contract test GET /discovery/info in `app/src/test/java/io/github/gauravyad69/speakershare/network/HostApiContractTest.kt`
- [x] **T005** [P] Contract test POST /clients/connect in `app/src/test/java/io/github/gauravyad69/speakershare/network/ClientConnectContractTest.kt`  
- [x] **T006** [P] Contract test POST /clients/{clientId}/disconnect in `app/src/test/java/io/github/gauravyad69/speakershare/network/ClientDisconnectContractTest.kt`
- [x] **T007** [P] Contract test GET /clients in `app/src/test/java/io/github/gauravyad69/speakershare/network/ClientsListContractTest.kt`
- [ ] **T008** [P] Contract test POST /clients/{clientId}/kick in `app/src/test/java/io/github/gauravyad69/speakershare/network/ClientKickContractTest.kt`
- [ ] **T009** [P] Contract test PUT /host/settings in `app/src/test/java/io/github/gauravyad69/speakershare/network/HostSettingsContractTest.kt`
- [ ] **T010** [P] Contract test GET /session/status in `app/src/test/java/io/github/gauravyad69/speakershare/network/SessionStatusContractTest.kt`

### UDP Protocol Tests
- [ ] **T011** [P] UDP packet format validation test in `app/src/test/java/io/github/gauravyad69/speakershare/network/UdpPacketFormatTest.kt`
- [ ] **T012** [P] UDP control commands test in `app/src/test/java/io/github/gauravyad69/speakershare/network/UdpControlCommandsTest.kt`
- [ ] **T013** [P] UDP discovery protocol test in `app/src/test/java/io/github/gauravyad69/speakershare/network/UdpDiscoveryTest.kt`
- [ ] **T014** [P] UDP audio streaming flow test in `app/src/test/java/io/github/gauravyad69/speakershare/network/UdpStreamingTest.kt`

### Integration Tests (User Scenarios)
- [ ] **T015** [P] Integration test: Basic host-client connection in `app/src/androidTest/java/io/github/gauravyad69/speakershare/BasicConnectionIntegrationTest.kt`
- [ ] **T016** [P] Integration test: Audio source switching in `app/src/androidTest/java/io/github/gauravyad69/speakershare/AudioSourceSwitchingIntegrationTest.kt`
- [ ] **T017** [P] Integration test: Volume and mute controls in `app/src/androidTest/java/io/github/gauravyad69/speakershare/VolumeControlsIntegrationTest.kt`
- [ ] **T018** [P] Integration test: Multiple clients connection in `app/src/androidTest/java/io/github/gauravyad69/speakershare/MultipleClientsIntegrationTest.kt`
- [ ] **T019** [P] Integration test: Client management (kick functionality) in `app/src/androidTest/java/io/github/gauravyad69/speakershare/ClientManagementIntegrationTest.kt`

---

## Phase 3.3: Core Implementation (ONLY after tests are failing)

### Data Models
- [x] **T020** [P] HostSession data class in `app/src/main/java/io/github/gauravyad69/speakershare/data/models/HostSession.kt`
- [x] **T021** [P] ClientConnection data class in `app/src/main/java/io/github/gauravyad69/speakershare/data/models/ClientConnection.kt`
- [x] **T022** [P] AudioStream data class in `app/src/main/java/io/github/gauravyad69/speakershare/data/models/AudioStream.kt`
- [x] **T023** [P] NetworkInfo data class in `app/src/main/java/io/github/gauravyad69/speakershare/data/models/NetworkInfo.kt`
- [x] **T024** [P] UserSettings data class in `app/src/main/java/io/github/gauravyad69/speakershare/data/models/UserSettings.kt`

### Core Services (Sequential - shared state)
- [x] **T025** AudioStreamManager service in `app/src/main/java/io/github/gauravyad69/speakershare/services/AudioStreamManager.kt`
- [x] **T026** HostService for session management in `app/src/main/java/io/github/gauravyad69/speakershare/services/HostService.kt`
- [x] **T027** ClientManager for connection handling in `app/src/main/java/io/github/gauravyad69/speakershare/services/ClientManager.kt`
- [ ] **T028** NetworkDiscoveryService (mDNS + UDP broadcast) in `app/src/main/java/io/github/gauravyad69/speakershare/services/NetworkDiscoveryService.kt`
- [ ] **T029** SettingsRepository for SharedPreferences in `app/src/main/java/io/github/gauravyad69/speakershare/data/repositories/SettingsRepository.kt`
- [ ] **T030** PermissionManager for Android permissions in `app/src/main/java/io/github/gauravyad69/speakershare/services/PermissionManager.kt`

### Audio Pipeline (Sequential - audio processing chain)
- [ ] **T031** AudioCaptureService (microphone + MediaProjection) in `app/src/main/java/io/github/gauravyad69/speakershare/audio/AudioCaptureService.kt`
- [ ] **T032** AudioEncoder (PCM to AAC) in `app/src/main/java/io/github/gauravyad69/speakershare/audio/AudioEncoder.kt`
- [ ] **T033** AudioDecoder (AAC to PCM) in `app/src/main/java/io/github/gauravyad69/speakershare/audio/AudioDecoder.kt`
- [ ] **T034** AudioPlaybackService (AudioTrack) in `app/src/main/java/io/github/gauravyad69/speakershare/audio/AudioPlaybackService.kt`
- [ ] **T035** AudioBufferManager for latency optimization in `app/src/main/java/io/github/gauravyad69/speakershare/audio/AudioBufferManager.kt`

---

## Phase 3.4: Network & Transport Integration

### WebRTC Implementation
- [ ] **T036** WebRTCManager setup and configuration in `app/src/main/java/io/github/gauravyad69/speakershare/network/WebRTCManager.kt`
- [ ] **T037** WebRTC signaling server (WebSocket) in `app/src/main/java/io/github/gauravyad69/speakershare/network/SignalingServer.kt`
- [ ] **T038** WebRTC client connection handler in `Theseapp/src/main/java/io/github/gauravyad69/speakershare/network/WebRTCClient.kt`

### UDP Fallback Implementation  
- [ ] **T039** UDP server for audio streaming in `app/src/main/java/io/github/gauravyad69/speakershare/network/UdpAudioServer.kt`
- [ ] **T040** UDP client for audio receiving in `app/src/main/java/io/github/gauravyad69/speakershare/network/UdpAudioClient.kt`
- [ ] **T041** UDP packet encoder/decoder in `app/src/main/java/io/github/gauravyad69/speakershare/network/UdpPacketHandler.kt`

### HTTP API Implementation
- [ ] **T042** Ktor HTTP server setup in `app/src/main/java/io/github/gauravyad69/speakershare/network/HttpApiServer.kt`
- [ ] **T043** Host API endpoints implementation in `app/src/main/java/io/github/gauravyad69/speakershare/network/HostApiHandler.kt`
- [ ] **T044** HTTP client for discovery in `app/src/main/java/io/github/gauravyad69/speakershare/network/DiscoveryClient.kt`

---

## Phase 3.5: User Interface (Compose)

### UI Components (Parallel - independent screens)
- [x] **T045** [P] MainActivity and navigation setup in `app/src/main/java/io/github/gauravyad69/speakershare/MainActivity.kt`
- [ ] **T046** [P] Mode selection screen (Host/Client) in `app/src/main/java/io/github/gauravyad69/speakershare/ui/screens/ModeSelectionScreen.kt`
- [ ] **T047** [P] Host screen with controls in `app/src/main/java/io/github/gauravyad69/speakershare/ui/screens/HostScreen.kt`
- [ ] **T048** [P] Client screen with volume controls in `app/src/main/java/io/github/gauravyad69/speakershare/ui/screens/ClientScreen.kt`
- [ ] **T049** [P] Host discovery list screen in `app/src/main/java/io/github/gauravyad69/speakershare/ui/screens/DiscoveryScreen.kt`
- [ ] **T050** [P] Settings screen in `app/src/main/java/io/github/gauravyad69/speakershare/ui/screens/SettingsScreen.kt`
- [ ] **T051** [P] Connected clients management screen in `app/src/main/java/io/github/gauravyad69/speakershare/ui/screens/ClientsScreen.kt`
- [ ] **T052** [P] Permission request dialogs in `app/src/main/java/io/github/gauravyad69/speakershare/ui/components/PermissionDialogs.kt`

---

## Phase 3.6: Background Services & Polish

### Background Operation
- [ ] **T053** Foreground service for background audio in `app/src/main/java/io/github/gauravyad69/speakershare/services/AudioForegroundService.kt`
- [ ] **T054** Notification controls for playback in `app/src/main/java/io/github/gauravyad69/speakershare/services/AudioNotificationManager.kt`

---

## Dependencies

### Critical Path Dependencies
```
Setup (T001-T003) → Tests (T004-T019) → Models (T020-T024) → Services (T025-T030) → Audio Pipeline (T031-T035) → Network (T036-T044) → UI (T045-T052) → Background Services (T053-T054)
```

### Specific Blocking Dependencies
- **T025 (AudioStreamManager)** blocks T031-T035, T036-T041
- **T026 (HostService)** blocks T042-T043, T047, T051
- **T027 (ClientManager)** blocks T044, T048, T049
- **T030 (PermissionManager)** blocks T052, T053
- **T042 (HTTP Server)** blocks T043, T044
- **T045 (MainActivity)** blocks T046-T052

### Parallel Execution Groups
```
Group 1 (Setup): T001, T002, T003 can run in sequence
Group 2 (Contract Tests): T004-T010 can run in parallel
Group 3 (UDP Tests): T011-T014 can run in parallel  
Group 4 (Integration Tests): T015-T019 can run in parallel
Group 5 (Models): T020-T024 can run in parallel
Group 6 (UI Screens): T046-T052 can run in parallel (after T045)
```

## Parallel Example
```bash
# Launch all contract tests together after setup:
Task: "Contract test GET /discovery/info in app/src/test/.../HostApiContractTest.kt"
Task: "Contract test POST /clients/connect in app/src/test/.../ClientConnectContractTest.kt"
Task: "Contract test POST /clients/{clientId}/disconnect in app/src/test/.../ClientDisconnectContractTest.kt"
Task: "Contract test GET /clients in app/src/test/.../ClientsListContractTest.kt"
Task: "Contract test POST /clients/{clientId}/kick in app/src/test/.../ClientKickContractTest.kt"
Task: "Contract test PUT /host/settings in app/src/test/.../HostSettingsContractTest.kt"
Task: "Contract test GET /session/status in app/src/test/.../SessionStatusContractTest.kt"
```

## Validation Checklist
*GATE: Verified before task execution*

✅ **All Contracts Covered**
- [x] 7 HTTP API endpoints have contract tests (T004-T010)
- [x] UDP protocol has comprehensive tests (T011-T014)  
- [x] WebSocket signaling tested in integration tests

✅ **All Entities Modeled**  
- [x] 5 data models from data-model.md (T020-T024)
- [x] All enums and validation rules included
- [x] Entity relationships properly structured

✅ **All User Scenarios Tested**
- [x] 5 quickstart scenarios covered (T015-T019)
- [x] Edge cases included in integration tests
- [x] Performance requirements testable

✅ **TDD Compliance**
- [x] All tests (T004-T019) before implementation (T020+)
- [x] Tests designed to fail initially
- [x] Implementation tasks make tests pass

✅ **Parallel Safety**
- [x] [P] tasks use different files
- [x] No shared state conflicts in parallel tasks
- [x] Dependencies clearly documented

## Notes
- **[P] tasks** = different files, no dependencies, can run simultaneously
- **Verify tests fail** before starting implementation phase
- **Commit after each task** for proper version control
- **Audio latency testing** required at each pipeline stage
- **Network reliability testing** with multiple devices recommended

## Performance Testing Requirements
Each audio pipeline task (T031-T035) must include:
- Latency measurement (<200ms target)
- Memory usage monitoring (<100MB active streaming)
- CPU usage validation (<15% audio processing)
- Battery impact assessment (<10%/hour background streaming)
