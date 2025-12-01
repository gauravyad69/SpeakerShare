Let me analyze the transport mechanisms for audio and video: 

Searched for text `broadcastAudio`, 2 results

Searched for text `ScreenStreamServer`, no results

Searched for text `WebRTCManager`, 20 results

Searched for text `screenBroadcast`, no results

Searched for text `ScreenCaptureService`, 19 results

Summarized conversation history

Read [](file:///home/mrhell/Projects/speakershare/SpeakerShare/app/src/main/java/io/github/gauravyad69/speakershare/network/HttpApiServer.kt#100-100), lines 100 to 380

Summarized conversation history

Read [](file:///home/mrhell/Projects/speakershare/SpeakerShare/app/src/main/java/io/github/gauravyad69/speakershare/network/HttpApiServer.kt#380-380), lines 380 to 430

Summarized conversation historyNow let me also check the audio pipeline to give a complete comparison: Optimizing tool selection...

Searched for files matching `**/UdpAudioServer.kt`, 1 match

Read [](file:///home/mrhell/Projects/speakershare/SpeakerShare/app/src/main/java/io/github/gauravyad69/speakershare/network/UdpAudioServer.kt#1-1), lines 1 to 100

Read [](file:///home/mrhell/Projects/speakershare/SpeakerShare/app/src/main/java/io/github/gauravyad69/speakershare/network/UdpAudioServer.kt#150-150), lines 150 to 200

Now I have the complete picture. Let me explain the transport mechanisms:

## Transport Mechanism Comparison

### **Audio Transport (UDP + AAC Codec)**
```
📱 Host                                           📱 Client
┌──────────────────────────────────────────────────────────────────┐
│ AudioCaptureService (PCM)                                        │
│        ↓                                                         │
│ AudioEncoder (PCM → AAC)  ← Encoding latency                     │
│        ↓                                                         │
│ UdpAudioServer.broadcastAudio()                                  │
│        ↓ (UDP packets over WiFi)                                 │
│        └─────────────────────────────────────────┐               │
│                                                  ↓               │
│                                     UdpAudioClient.kt            │
│                                            ↓                     │
│                                     AudioDecoder (AAC → PCM) ← Decoding latency
│                                            ↓                     │
│                                     AudioPlaybackService         │
│                                     (buffered playback) ← Playback buffer
└──────────────────────────────────────────────────────────────────┘
```

**Latency sources in audio:**
1. **AAC Encoding** (~20-40ms) - converting PCM to compressed AAC
2. **Multiple buffer queues** - capture buffer, encoder buffer, decoder buffer
3. **AAC Decoding** (~20-40ms) - converting AAC back to PCM
4. **AudioTrack playback buffer** (~20-50ms) - hardware buffer for smooth playback

### **Video/Screen Transport (HTTP + JPEG)**
```
📱 Host                                           📱 Client
┌──────────────────────────────────────────────────────────────────┐
│ ScreenCaptureService                                             │
│        ↓                                                         │
│ screenFrameFlow (raw JPEG bytes)                                 │
│        ↓                                                         │
│ HttpApiServer /api/screen/frame                                  │
│        ↓ (HTTP response over WiFi)                               │
│        └─────────────────────────────────────────┐               │
│                                                  ↓               │
│                                     Client receives JPEG         │
│                                            ↓                     │
│                                     Display directly (no decode) │
└──────────────────────────────────────────────────────────────────┘
```

**Why video is faster:**
1. **No codec pipeline** - JPEG is already compressed in the screen capture step
2. **Single buffer** - frame goes directly from capture to display
3. **No playback buffering** - images display instantly, no hardware buffer needed
4. **Polling model** - client requests frames when ready, no accumulation

### Key Insight

**Audio MUST be buffered** for smooth playback - choppy audio is very noticeable to humans. **Video can tolerate frame drops** - the human eye just sees slightly lower framerate.

### Possible Optimizations

1. **Use raw PCM instead of AAC** - removes ~40-80ms of codec latency but uses 10x more bandwidth
2. **Use Opus codec** - designed for low latency (5-10ms vs AAC's 20-40ms)
3. **Reduce sample rate to 22050 Hz** - less data to process
4. **Use shorter AAC frames** - but increases overhead

Would you like me to implement any of these audio optimizations?