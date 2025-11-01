# Quickstart Guide: Real-Time Audio Broadcasting Android App

**Purpose**: Validate core user scenarios and test integration points  
**Estimated Time**: 15 minutes  
**Prerequisites**: Two Android devices on same Wi-Fi network

## Test Scenarios

### Scenario 1: Basic Host-Client Connection (5 minutes)

**Goal**: Verify host can broadcast microphone and client can connect and hear audio.

#### Setup Steps
1. **Host Device**:
   - Launch SpeakerShare app
   - Grant microphone permission when prompted
   - Select "Host Mode" 
   - Enter session name (e.g., "My Broadcast")
   - Tap "Start Broadcasting"
   - Verify status shows "Broadcasting - 0 clients"

2. **Client Device**:
   - Launch SpeakerShare app on second device
   - Select "Joinee Mode"
   - Wait for host to appear in discovery list (should take <5 seconds)
   - Tap discovered host to connect
   - Verify audio starts playing through speakers

#### Validation Checks
- [ ] Host shows "1 client connected"
- [ ] Client shows "Connected to [Host Name]" 
- [ ] Client can hear host's microphone audio clearly
- [ ] Audio latency feels natural (<200ms perceived delay)
- [ ] Notification appears on host device showing active broadcast

**Expected Result**: ✅ Basic audio streaming works end-to-end

**Common Issues**:
- Missing drawable resources: Ensure all notification icons (ic_notification, ic_stop, ic_pause, ic_play_arrow, ic_volume_off, ic_volume_up) are created
- Permission errors: Check that RECORD_AUDIO, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE are granted
- WebRTC initialization errors: Verify PeerConnectionFactory is properly initialized with context

---

### Scenario 2: Audio Source Switching (3 minutes)

**Goal**: Verify host can switch between microphone and system audio during broadcast.

#### Test Steps  
1. **While connected from Scenario 1**:
   - Host: Tap "Switch to System Audio"
   - Host: Play music or video on device
   - Client: Verify audio switches from microphone to system audio
   - Host: Tap "Switch to Microphone" 
   - Client: Verify audio switches back to microphone

#### Validation Checks
- [ ] Audio source switching works without disconnecting client
- [ ] System audio (music/video) is clearly audible on client
- [ ] Switching back to microphone works seamlessly
- [ ] No audio dropouts during source changes

**Expected Result**: ✅ Audio source switching works seamlessly

---

### Scenario 3: Volume and Mute Controls (2 minutes)

**Goal**: Verify individual client volume control and muting functionality.

#### Test Steps
1. **Client Controls**:
   - Adjust volume slider to 50%
   - Verify audio becomes quieter
   - Tap mute button
   - Verify audio stops completely
   - Tap unmute button
   - Verify audio resumes at previous volume

2. **Host Controls**:
   - Host: Tap mute button
   - Client: Verify no audio received
   - Host: Tap unmute
   - Client: Verify audio resumes

#### Validation Checks
- [ ] Client volume control works independently
- [ ] Client mute/unmute works correctly
- [ ] Host mute affects all clients
- [ ] Volume changes don't affect other clients (if multiple)

**Expected Result**: ✅ Audio controls work as expected

---

### Scenario 4: Multiple Clients (3 minutes)

**Goal**: Verify multiple clients can connect simultaneously.

#### Test Steps
1. **Add Second Client**:
   - Connect third Android device to same Wi-Fi
   - Launch app in Joinee Mode
   - Connect to same host
   - Verify both clients receive audio

2. **Independent Controls**:
   - First client: Set volume to 25%
   - Second client: Set volume to 75%  
   - Verify each client has different volume levels
   - First client: Mute audio
   - Verify second client still receives audio

#### Validation Checks
- [ ] Host shows "2 clients connected"
- [ ] Both clients receive audio simultaneously
- [ ] Volume controls work independently for each client
- [ ] Muting one client doesn't affect the other

**Expected Result**: ✅ Multiple clients work independently

---

### Scenario 5: Client Management (2 minutes)

**Goal**: Verify host can manage connected clients.

#### Test Steps
1. **View Clients**:
   - Host: Tap "Connected Clients" 
   - Verify list shows both connected clients with names/IPs

2. **Kick Client**:
   - Host: Select first client and tap "Kick"
   - Verify client is disconnected
   - Client: Verify shows "Disconnected by host"
   - Host: Verify shows "1 client connected"

#### Validation Checks
- [ ] Host can view connected clients list
- [ ] Client names and IPs are displayed correctly
- [ ] Kick functionality works immediately
- [ ] Kicked client receives disconnect notification
- [ ] Remaining clients unaffected by kick action

**Expected Result**: ✅ Client management works correctly

---

## Network Fallback Test (Optional - 3 minutes)

**Goal**: Verify UDP fallback when WebRTC fails.

#### Test Steps
1. **Force UDP Mode**:
   - Enable "Force UDP Mode" in developer settings
   - Repeat Scenario 1 with UDP transport
   - Verify audio quality and latency acceptable

#### Validation Checks
- [ ] UDP connection establishes successfully  
- [ ] Audio quality comparable to WebRTC
- [ ] Latency still feels acceptable
- [ ] Client shows "UDP Transport" in connection info

**Expected Result**: ✅ UDP fallback provides acceptable experience

---

## Edge Case Validation

### Network Interruption Recovery
1. **Wi-Fi Interruption**:
   - Temporarily disconnect host from Wi-Fi
   - Reconnect after 10 seconds
   - Verify clients can reconnect automatically

### Background Operation
1. **Host Background**:
   - Put host app in background
   - Verify clients continue receiving audio
   - Verify foreground notification appears

### Permission Denial Handling
1. **Audio Permission**:
   - Deny microphone permission initially
   - Verify graceful error message
   - Grant permission and retry

---

## Success Criteria

**All scenarios must pass for successful validation**:

✅ **Core Functionality**
- [ ] Host-client audio streaming works
- [ ] Audio source switching works
- [ ] Volume and mute controls work
- [ ] Multiple clients supported  
- [ ] Client management works

✅ **Quality Standards**
- [ ] Audio latency < 200ms (perceived)
- [ ] No audio dropouts during normal operation
- [ ] Clear audio quality at default settings
- [ ] Responsive UI (no freezing)

✅ **Network Reliability**
- [ ] Auto-discovery works reliably
- [ ] Connection establishment < 5 seconds
- [ ] Graceful handling of network issues
- [ ] UDP fallback functions when needed

✅ **Edge Cases**
- [ ] Permission handling works gracefully
- [ ] Background operation maintained
- [ ] Network recovery works automatically
- [ ] Error messages are clear and actionable

---

## Troubleshooting

### Common Issues

**"No hosts found" in client**:
- Verify both devices on same Wi-Fi network
- Check host is actually broadcasting
- Try manual IP entry option

**"Connection failed"**:
- Check firewall settings on host device
- Verify network allows peer-to-peer communication
- Try restarting both apps

**"Audio choppy or delayed"**:
- Check Wi-Fi signal strength
- Try lower audio quality setting
- Ensure host device not under heavy CPU load

**"Permission denied" errors**:
- Grant all requested permissions
- Check Android settings for microphone/audio access
- Restart app after granting permissions

### Getting Help
- Check app logs in developer settings
- Test with different devices/network
- Report issues with device models and Android versions

---

**Next Steps**: If all scenarios pass, the implementation meets requirements and is ready for production testing.
