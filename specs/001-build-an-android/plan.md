
# Implementation Plan: Real-Time Audio Broadcasting Android App

**Branch**: `001-build-an-android` | **Date**: September 17, 2025 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-build-an-android/spec.md`

## Execution## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [x] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS
- [x] All NEEDS CLARIFICATION resolved
- [x] Complexity deviations documentedmand scope)
```
1. Load feature spec from Input path
   → If not found: ERROR "No feature spec at {path}"
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → Detect Project Type from context (web=frontend+backend, mobile=app+api)
   → Set Structure Decision based on project type
3. Fill the Constitution Check section based on the content of the constitution document.
4. Evaluate Constitution Check section below
   → If violations exist: Document in Complexity Tracking
   → If no justification possible: ERROR "Simplify approach first"
   → Update Progress Tracking: Initial Constitution Check
5. Execute Phase 0 → research.md
   → If NEEDS CLARIFICATION remain: ERROR "Resolve unknowns"
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, agent-specific template file (e.g., `CLAUDE.md` for Claude Code, `.github/copilot-instructions.md` for GitHub Copilot, or `GEMINI.md` for Gemini CLI).
7. Re-evaluate Constitution Check section
   → If new violations: Refactor design, return to Phase 1
   → Update Progress Tracking: Post-Design Constitution Check
8. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md)
9. STOP - Ready for /tasks command
```

**IMPORTANT**: The /plan command STOPS at step 7. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
Android app with dual Host/Client modes for real-time audio broadcasting over LAN. Host broadcasts microphone or system audio to multiple clients with individual volume control, auto-discovery, and client management. Technical approach includes dual implementation: primary WebRTC solution and fallback UDP with AAC encoding, using Ktor for communication.

## Technical Context
**Language/Version**: Kotlin for Android (API level 21+), Compose UI  
**Primary Dependencies**: 
- WebRTC: `io.getstream:stream-webrtc-android-compose:$version` (primary solution)
- Ktor for communication and HTTP server
- AAC/MP3 encoding libraries for UDP fallback
- Kotlin Coroutines for async operations
**Storage**: SharedPreferences for user settings, in-memory for session state  
**Testing**: JUnit 4, Espresso for UI testing, MockK for mocking  
**Target Platform**: Android 5.0+ (API 21), supports Wi-Fi and mobile hotspot  
**Project Type**: mobile - Android single-module app with dual implementation  
**Performance Goals**: 
- Audio latency < 200ms for real-time streaming
- Support unlimited concurrent clients (device-limited)
- Default 128kbps AAC audio quality (tunable)
**Constraints**: 
- LAN-only operation (no internet required)
- Real-time audio processing
- Background operation support
- Android permission handling (RECORD_AUDIO, ACCESS_NETWORK_STATE, etc.)
**Scale/Scope**: 
- Single Android app with 10-15 screens
- Dual audio transport implementations
- Comprehensive permission management
- Auto-discovery and connection management

**User-Provided Implementation Details**: 
1. Dual approach: WebRTC (preferred) and UDP+AAC fallback
2. Unlimited concurrent clients (device hardware limited)
3. Tunable audio quality, default 128kbps AAC
4. <200ms latency requirement
5. Ktor for communication layer

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Initial Assessment**: 
- ✅ Single Android module approach aligns with simplicity
- ⚠️ Dual implementation complexity (WebRTC + UDP) requires justification
- ✅ Clear separation of concerns (Host/Client modes)
- ✅ Test-first approach planned for both implementations
- ✅ Standard Android architecture patterns

**Post-Design Re-evaluation**:
- ✅ Data model remains simple with clear entity relationships
- ✅ API contracts follow REST conventions
- ✅ No unnecessary abstractions introduced
- ✅ Implementation complexity contained within transport layer
- ✅ Clear error handling and fallback strategies

**Justification for Complexity**:
- Dual implementation needed for reliability: WebRTC for quality, UDP for compatibility
- Network conditions vary significantly, fallback ensures functionality
- Both implementations share common interfaces, reducing actual complexity
- Transport abstraction isolates complexity from business logic

## Project Structure

### Documentation (this feature)
```
specs/[###-feature]/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
# Option 1: Single project (DEFAULT)
src/
├── models/
├── services/
├── cli/
└── lib/

tests/
├── contract/
├── integration/
└── unit/

# Option 2: Web application (when "frontend" + "backend" detected)
backend/
├── src/
│   ├── models/
│   ├── services/
│   └── api/
└── tests/

frontend/
├── src/
│   ├── components/
│   ├── pages/
│   └── services/
└── tests/

# Option 3: Mobile + API (when "iOS/Android" detected)
api/
└── [same as backend above]

ios/ or android/
└── [platform-specific structure]
```

**Structure Decision**: Option 1 (Single Android Project) - Pure Android app without separate API backend

## Phase 0: Outline & Research
1. **Extract unknowns from Technical Context** above:
   - For each NEEDS CLARIFICATION → research task
   - For each dependency → best practices task
   - For each integration → patterns task

2. **Generate and dispatch research agents**:
   ```
   For each unknown in Technical Context:
     Task: "Research {unknown} for {feature context}"
   For each technology choice:
     Task: "Find best practices for {tech} in {domain}"
   ```

3. **Consolidate findings** in `research.md` using format:
   - Decision: [what was chosen]
   - Rationale: [why chosen]
   - Alternatives considered: [what else evaluated]

**Output**: research.md with all NEEDS CLARIFICATION resolved

## Phase 1: Design & Contracts
*Prerequisites: research.md complete*

1. **Extract entities from feature spec** → `data-model.md`:
   - Entity name, fields, relationships
   - Validation rules from requirements
   - State transitions if applicable

2. **Generate API contracts** from functional requirements:
   - For each user action → endpoint
   - Use standard REST/GraphQL patterns
   - Output OpenAPI/GraphQL schema to `/contracts/`

3. **Generate contract tests** from contracts:
   - One test file per endpoint
   - Assert request/response schemas
   - Tests must fail (no implementation yet)

4. **Extract test scenarios** from user stories:
   - Each story → integration test scenario
   - Quickstart test = story validation steps

5. **Update agent file incrementally** (O(1) operation):
   - Run `.specify/scripts/bash/update-agent-context.sh copilot` for your AI assistant
   - If exists: Add only NEW tech from current plan
   - Preserve manual additions between markers
   - Update recent changes (keep last 3)
   - Keep under 150 lines for token efficiency
   - Output to repository root

**Output**: data-model.md, /contracts/*, failing tests, quickstart.md, agent-specific file

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
- Load `.specify/templates/tasks-template.md` as base
- Generate tasks from Phase 1 design docs (data-model.md, contracts/, quickstart.md)
- Each API endpoint → contract test task [P]
- Each data model entity → model implementation task [P]
- Each user scenario from quickstart → integration test task
- WebRTC and UDP implementations as separate parallel tracks
- UI components derived from user stories

**Specific Task Categories**:
1. **Data Models** (5 tasks) - HostSession, ClientConnection, AudioStream, NetworkInfo, UserSettings [P]
2. **Contract Tests** (7 tasks) - One per API endpoint from host-api.md [P]
3. **UDP Protocol Tests** (4 tasks) - Core UDP protocol functionality [P]
4. **WebRTC Integration** (6 tasks) - WebRTC setup, signaling, streaming
5. **Audio Pipeline** (5 tasks) - Capture, encoding, playback, source switching
6. **Network Discovery** (3 tasks) - mDNS, UDP broadcast, service registration
7. **UI Components** (8 tasks) - Host screen, Client screen, Settings, Connection management
8. **Permission Handling** (3 tasks) - Audio permissions, network permissions, system audio
9. **Background Services** (2 tasks) - Foreground service, notification management
10. **Integration Tests** (5 tasks) - End-to-end scenarios from quickstart.md

**Ordering Strategy**:
- **Phase A**: Data models and contract tests (all [P] - parallel execution)
- **Phase B**: Core services (HostService, ClientManager, AudioStreamManager)
- **Phase C**: Network layer (Discovery, WebRTC setup, UDP fallback)
- **Phase D**: Audio pipeline (Capture, encoding, playback)
- **Phase E**: UI layer (Compose screens, ViewModels)
- **Phase F**: Integration and testing (End-to-end scenarios)

**Estimated Output**: 41 numbered, ordered tasks in tasks.md with clear dependencies

**Quality Gates**:
- All contract tests must pass before implementation
- Audio latency testing at each pipeline stage
- Network reliability testing with multiple clients
- Permission flow testing on different Android versions

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)  
**Phase 4**: Implementation (execute tasks.md following constitutional principles)  
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |


## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [ ] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS  
- [x] All NEEDS CLARIFICATION resolved
- [x] Complexity deviations documented

---
*Based on Constitution v2.1.1 - See `/memory/constitution.md`*
