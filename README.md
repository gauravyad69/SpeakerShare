# SpeakerShare

Turn spare Android phones into a wireless speaker system. One phone broadcasts its microphone or system audio, and every other phone on the same Wi-Fi or hotspot can listen in — no internet, no accounts, nothing leaves the local network.

It also does synced group playback: the host picks local audio or video files, transfers them to each listener, and every device plays them back at the same time. Useful for listening to the same music across a few phones, or for watch parties where everyone has their own screen and earphones but wants the same video.

A few things it's good for:

- Making a cheap multi-room PA out of old phones
- Sharing audio from a presentation or movie to people without Bluetooth splitters
- Group listening in places with bad or no connectivity — it works fully offline on a hotspot

## Requirements

- Android 6.0 (API 23) or newer on every device
- All devices on the same network — either the same Wi-Fi, or the host phone's hotspot
- Capturing audio from other apps (system audio) requires Android 10+; microphone broadcast and group playback work on 6.0+

The APK is built with 16 KB page-size aligned native libraries, so it runs fine on newer devices (Galaxy S25 and friends) that reject 32-bit-aligned builds.

## Install

Download the latest APK from the [releases page](https://github.com/gauravyad69/SpeakerShare/releases) and sideload it on every phone you plan to use — the host and all the listeners. Android will ask you to allow installs from whatever app you used to download the file; that's expected for sideloading.

## Using it

### Live audio broadcast

1. On the phone that will be the source: **START BROADCAST**, pick microphone or system audio, grant the permissions when asked. For system audio you'll get a screen-recording prompt — this is how Android requires apps to capture internal audio.
2. On each listener phone: **JOIN BROADCAST**. The app discovers hosts on the network automatically (mDNS with a UDP broadcast fallback). If discovery doesn't find the host, there's a manual connect option where you type in the host's IP, shown on the host screen.
3. The host can mute, switch between mic and system audio mid-broadcast, see connected listeners, and kick anyone who shouldn't be there.

Listeners that drop off Wi-Fi reconnect automatically when the network comes back.

### Group playback (Listen/Watch Together)

1. On the host: **LISTEN TOGETHER** or **WATCH TOGETHER** (same screen, just filters the file picker for audio or video) and create a session.
2. Listeners join from the same screen. Files are transferred to each listener over HTTP and cached, so a 50 MB video needs to be sent once per listener — after that, skipping around is instant.
3. Play, pause, seek, and track changes are pushed to everyone over WebSocket and scheduled against a synchronized clock, so all devices stay within a few tens of milliseconds of each other. The sync screen shows the current drift and its verdict.

The host is the source of truth: if it leaves, the session ends. Listeners keep their cached copies of the files, which get reused (verified by SHA-256) the next time the same file is played.

## How it works

Short version:

- **Live audio** is a custom UDP protocol — AAC-LC (or raw PCM on the lowest-latency setting) in packets with a 28-byte header, sent from the host to each registered listener. Latency profile settings trade quality for delay.
- **Group playback** runs a Ktor HTTP + WebSocket server on the host. Files are fetched by hash and cached; playback commands carry timestamps in the host's clock, and each device maps that onto its own clock using an NTP-style offset estimate with continuous drift correction.
- **Discovery** is NSD/mDNS (`_speakershare._tcp`, plus a `SyncedPlay-<model>` service for group playback) with a UDP broadcast fallback, and it prefers the hotspot interface when the host is sharing its connection.

Details, including the wire format and the sync math, are in [docs/architecture.md](docs/architecture.md) and [docs/udp-protocol.md](docs/udp-protocol.md).

## Troubleshooting

- **Discovery finds nothing** — some routers isolate wireless clients from each other ("AP isolation" / "client isolation"). Switch that off in the router, or use the host phone's hotspot instead, which always works. Manual connect via IP is the escape hatch.
- **Host not visible on hotspot** — tap the refresh button in the top bar; discovery restarts. The app tries to advertise on the hotspot's interface specifically, but a few device ROMs are stubborn about mDNS on tethering interfaces; the UDP fallback usually catches these within a few seconds.
- **Choppy audio** — pick a lower latency profile (lower bitrate) in the host settings, or move closer to the access point. Raw PCM mode is uncompressed and needs a solid link.
- **Synced playback drifts after seeking** — the clock converges over a few seconds after each intentional seek. If it stays bad, check the drift indicator; anything under ~75 ms is generally imperceptible for music.
- **App killed in background** — the app uses foreground services, but some OEMs (MIUI, EMUI, and others) are aggressive. Exempt the app from battery optimization when prompted, or lock it in recents if your ROM supports that.

## Building

Standard Android project. You need JDK 11+ and the Android SDK (compileSdk 34).

```
./gradlew :app:assembleDebug
```

Unit tests:

```
./gradlew :app:testDebugUnitTest
```

Release builds sign automatically if a `keystore.properties` file exists at the repo root (see `app/build.gradle.kts`); otherwise you get an unsigned APK. Something like:

```
storeFile=app/release-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Known limitations

- Live audio latency is on the order of 150–300 ms depending on the profile and the device's audio pipeline. It's fine for music and speech, noticeable for drums.
- The WebRTC stack in the codebase is only partially wired up; the working live-audio path is UDP. Opus over UDP would likely cut latency further — patches welcome.
- Group playback sync targets music/movie listening, not lip-synced live performance. Tens of milliseconds of drift, not zero.
- No encryption or authentication on any of the protocols. Fine on your own hotspot; don't run it on conference Wi-Fi.

## Contributing

Issues and pull requests are welcome. Keep changes small and describable — this is a hobby project with a small surface area, and I'd like to keep it that way.

## License

GPL-3.0 — see [LICENSE](LICENSE). In short: use it, study it, share it, and if you distribute a modified version, the source goes with it.
