# Architecture

This is a tour of how SpeakerShare works on the inside, current as of v1.0.0. It's written for someone changing the code, so it favors accuracy over aspiration — where something is half-wired, it says so.

The app is a single Android module under `app/src/main/java/io/github/gauravyad69/speakershare/` with these packages:

```
audio/          capture (AudioRecord), AAC encode/decode, AudioTrack playback
network/        UDP audio server/client, HTTP API server, discovery, WebRTC (partial)
network/udp/    UDP transport interface
screen/         screen capture for the screen-share experiment
media/sync/     the whole group-playback stack (server, players, clock sync, file transfer)
services/       foreground services + host/client/discovery managers
data/           models and repositories (settings, sessions)
ui/             Compose screens, viewmodels, navigation, theme
```

## The two features

Both features follow the same shape: one phone is the host and runs servers; the others are clients that connect to it. Nothing goes through the internet — the host advertises itself on the LAN, and clients find it via discovery.

They don't share a transport. Live audio is UDP; group playback is HTTP + WebSocket. They do share discovery, the network-preference logic, and the foreground-service plumbing.

## Live audio broadcast

The pipeline, host to client:

```
AudioRecord (PCM)  →  MediaCodec AAC encoder  →  UdpAudioServer  →  network
                                                              ↓
AudioTrack (PCM)  ←  MediaCodec AAC decoder  ←  UdpAudioClient ←
```

- **Capture** (`audio/AudioCaptureService.kt`): `AudioRecord` at 44.1 kHz mono by default. Two sources: `MIC`, or `AudioPlaybackCaptureConfiguration` via MediaProjection for system audio (Android 10+, captures `USAGE_MEDIA`/`USAGE_GAME`/`USAGE_UNKNOWN`). The MediaProjection consent dialog is why system-audio mode looks like starting a screen recording — that's the OS's requirement, not a bug.
- **Encode** (`audio/AudioEncoder.kt`): MediaCodec AAC-LC, raw AAC (no ADTS). The decoder needs the 2-byte Audio Specific Config ("csd-0") or it silently produces nothing — see gotchas below.
- **Transport** (`network/UdpAudioServer.kt`, `UdpAudioClient.kt`): custom packets (see [udp-protocol.md](udp-protocol.md)). The client is a passive listener: it binds a known port (9091), sends a CONTROL_CONNECT to the host's 9090, and the host then sends audio to every registered client's `ip:9091`. No ACKs per audio packet, no retransmission — lost packets become brief silence.
- **Playback** (`audio/AudioPlaybackService.kt`): `AudioTrack` in streaming mode with a jitter buffer; the client-side buffer target is what smooths over Wi-Fi variance at the cost of latency.

Latency profiles (`data/model/HostSession.kt`) pick the codec settings:

| Profile | Encoding | Settings |
|---|---|---|
| NO_LATENCY | raw PCM | 22.05 kHz mono, no codec (packet type 0x05) |
| LOW_LATENCY | AAC-LC | 44.1 kHz mono, 96 kbps |
| BALANCED (default) | AAC-LC | 44.1 kHz mono, 128 kbps |
| HIGH_QUALITY | AAC-LC | 48 kHz mono, 192 kbps |

The HTTP API server (`network/HttpApiServer.kt`, port 8080) handles session info, client registration for the UI, and kicking.

**WebRTC status**: there's a `WebRTCManager` (host-side, Google STUN), a `WebRTCClient` + `SignalingServer` (port 8081), and a `network/webrtc/` handler package, but none of it is wired into the end-to-end path — `HostService` passes transport `"UDP"` explicitly and the client has no WebRTC receive path. Treat it as scaffolding for a future lower-latency transport.

## Group playback (Listen/Watch Together)

Everything in `media/sync/`, served by a Ktor (Netty) server on port **8765** — HTTP and WebSocket on the same port. The mDNS service is `SyncedPlay-<Build.MODEL>` with a `mode=sync` TXT record to distinguish it from live-audio hosts.

### Session lifecycle

1. Host creates a session (`SyncedPlaybackManager`), picks files (audio or video — the two home-screen buttons only differ in the file picker's filter).
2. Each file gets a SHA-256 hash (`SyncedFileTransfer.calculateFileHash`); the host registers its local URI in the server.
3. Clients join over HTTP (`/sync/join`), then check each file's hash against their cache dir (`synced_media/` on internal storage). Missing files download over the WebSocket `/file/ws/{hash}` in 256 KB chunks with resume support (a `{"type":"resume","offset":N}` control frame), or plain `GET /file/{hash}` as fallback. After download the hash is verified again.
4. Playback commands flow host → clients; on leave/stop the session tears down. Cached files persist and are reused next time the same content is played.

### Sync design

The problem: "play at position X at time T" must mean the same instant on every phone, but every phone's wall clock differs, and they drift.

- **Clock offset**: at join, the client runs several NTP-style t1/t2/t3/t4 exchanges over `GET /sync/clock` and adopts the offset from the **minimum-RTT** sample — the exchange with the least queuing delay gives the least-biased offset estimate. (Median is kept as a sanity fallback; a single-sample WebSocket path was removed because one exchange turns jitter into offset error.)
- **Commands**: the host broadcasts JSON `SyncCommand`s (`play`, `pause`, `seek`, `switch`, `sync`, `stop`, `volume`) over `/sync/ws/{clientId}` with an HTTP polling fallback. A `play` command carries an absolute timestamp in host-clock time. The host schedules its own local player for exactly that instant too, so host and clients start from the same reference rather than host-then-broadcast.
- **Drift correction**: during playback the host sends periodic `sync` pulses with its current position/timestamp. Each client measures its own position at the same clock instant, computes drift, and feeds it to `ClockSynchronizer.recordDrift()` — a 5-sample moving average that nudges the clock offset (correction = avgDrift/4, applied every 5 s, bounded, with rejection heuristics for outliers and a warmup period). Intentional seeks and track switches clear the drift samples so the transient doesn't poison convergence.
- **When to re-seek**: if measured drift exceeds the tolerance (adjustable in settings, default 5000 ms min interval between corrective seeks), the client seeks to the correct position. Small drift is left alone — ExoPlayer position jumps are audible/visible, so the clock converges instead.

Player is ExoPlayer (`SyncedMediaPlayer.kt`) with deliberately lowered start buffers for local files (audio 1000 ms, video 2500 ms) since there's no network jitter in the media path itself — only the command path is networked.

Steady state on a phone-to-phone hotspot: 10–60 ms drift, which the UI grades and displays.

## Discovery

`services/NetworkDiscoveryService.kt` + `network/HotspotNetworkHelper.kt`.

Two mechanisms, whichever fires first:

1. **NSD/mDNS**: live-audio hosts advertise `_speakershare._tcp`, group playback advertises `SyncedPlay-<model>`. On API 34+ the host pins its advertised addresses to the hotspot (SoftAP) interface via `NsdServiceInfo.setHostAddresses()` — this matters because the default network on a tethering phone is usually the mobile-data interface, and advertising that address makes the service unresolvable-to-useless for clients on the hotspot.
2. **UDP broadcast**: the host broadcasts a JSON announcement to port 9089 on each broadcast-capable subnet (hotspot subnet first). This catches networks and ROMs where mDNS is filtered or broken.

One lesson paid for in debugging time: calling `NsdServiceInfo.setNetwork()` with the tethering network to "fix" address selection stalls registration entirely on some devices (`onServiceRegistered` never fires), which — because the UDP fallback was originally chained on that callback — silently killed both mechanisms. The UDP broadcast now starts immediately after `registerService()` with a double-start guard.

Manual connect (typing `host:port`) exists as the escape hatch and always will.

## Services and lifecycle

Two manifest-registered foreground services keep Android from killing things mid-stream:

- `AudioForegroundService` (`mediaProjection|microphone` type) — the live-audio host. Also owns the MediaProjection consent flow and source switching.
- `ClientForegroundService` (`mediaPlayback` type) — keeps a listener's network receive loop alive in background.

`HostService`, `ClientManager`, `NetworkDiscoveryService`, `AudioStreamManager` etc. are Hilt singletons, not Android services. The app requests battery-optimization exemption; some OEM ROMs ignore that and need the user to lock the app in recents manually.

## Gotchas paid for in blood

These are here because each one cost an afternoon. If you touch the audio pipeline, re-read this section first.

1. **Never mix absolute and relative `ByteBuffer` ops.** The original packet writer used `putInt(position, magic)` followed by relative `put(bytes)`; the relative write starts at the buffer's own position (0), silently overwrote the magic, and every packet was rejected. The fix is sequential relative writes only.
2. **AAC decoders are silent without csd-0.** MediaCodec AAC-LC will happily accept input and produce zero output if the MediaFormat lacks the Audio Specific Config. It's 2 bytes: audioObjectType 2 (AAC-LC) + sampling-frequency index + channel config. For 22.05 kHz mono it's `[0x13, 0x88]`.
3. **Sample rate must match at every stage.** Capture, encoder, decoder, and AudioTrack all need the same rate; any mismatch is garbled-but-"working" audio that looks like a network bug.
4. **The wire session ID is 8 bytes.** The full UUID doesn't fit in the packet header, so only the first 8 characters go on the wire. Client-side matching must fall back to source-IP comparison, because two sessions can share a truncated prefix.
5. **Clock offset from one sample is noise.** One t1/t2/t3/t4 exchange has jitter of tens of ms; adopting its offset directly bakes that into the clock. Minimum-RTT selection + drift convergence over time is what actually works.
