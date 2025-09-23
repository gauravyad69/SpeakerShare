<!--
Sync Impact Report:
- Version change: Template → 1.0.0
- New constitution creation for SpeakerShare Android audio broadcasting app
- Added principles: Simplicity-First, Test-Driven Development, Real-Time Performance, Android-Native Architecture, LAN-Only Security
- Added sections: Performance Standards, Development Workflow
- Templates requiring updates: ✅ Updated plan-template.md references / ⚠ spec-template.md and tasks-template.md may need alignment review
- Follow-up TODOs: None
-->

# SpeakerShare Constitution

## Core Principles

### I. Simplicity-First
Single-module Android architecture with clear separation of concerns. Dual transport implementations (WebRTC/UDP) are justified only when necessary for compatibility and reliability. Avoid unnecessary abstractions - favor composition over complex inheritance hierarchies. Each component must have a single, well-defined responsibility.

### II. Test-Driven Development (NON-NEGOTIABLE)
TDD mandatory: Contract tests written → Tests MUST fail → Implementation makes tests pass. Integration tests required for all user scenarios. All audio pipeline components must include latency, memory, and CPU performance assertions. Red-Green-Refactor cycle strictly enforced.

### III. Real-Time Performance
Audio streaming latency MUST be <200ms end-to-end. Memory usage MUST stay <100MB during active streaming. CPU usage for audio processing MUST be <15% on target devices. Battery impact MUST be <10%/hour during background streaming. Performance degradation requires architectural justification.

### IV. Android-Native Architecture
Follow Android architectural patterns: MVVM with Repository pattern, Jetpack Compose for UI, Kotlin Coroutines for async operations, Hilt for dependency injection. Handle Android lifecycle properly including app process termination and background restrictions. Proper permission handling is non-negotiable.

### V. LAN-Only Security
No internet connectivity required or allowed for core functionality. Local network operation ensures privacy and low latency. WebRTC and UDP protocols must use secure local-only configurations. Client authentication through network presence validation only.

## Performance Standards

Audio pipeline components must meet measurable performance criteria:
- Latency measurement at each stage with <200ms total budget
- Memory profiling with active monitoring during streaming
- CPU usage validation on minimum supported hardware (API 21+)
- Battery impact assessment for background operation compliance
- Network bandwidth optimization for multiple concurrent clients

## Development Workflow

Constitution supersedes all other development practices. All design decisions must pass constitutional compliance check before and after implementation. Complexity must be explicitly justified against simplicity principle. Contract tests must be written and failing before any implementation begins. Performance requirements must be validated at each audio pipeline stage.

## Governance

All feature development must verify constitutional compliance through the established gate system. Architecture changes require explicit complexity justification documented in plan artifacts. Test-first discipline is non-negotiable and must be validated before implementation phases. Performance standards must be continuously monitored and validated.

**Version**: 1.0.0 | **Ratified**: 2025-09-23 | **Last Amended**: 2025-09-23