package io.github.gauravyad69.speakershare.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.github.gauravyad69.speakershare.data.model.UserSettings
import io.github.gauravyad69.speakershare.ui.theme.*
import io.github.gauravyad69.speakershare.ui.components.DuolingoButton
import io.github.gauravyad69.speakershare.ui.viewmodels.SettingsViewModel

/**
 * Settings Screen for configuring app preferences and audio settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.userSettings.collectAsState()
    val audioQuality by viewModel.audioQuality.collectAsState()
    val bufferSize by viewModel.bufferSize.collectAsState()
    val latencyProfile by viewModel.latencyProfile.collectAsState()
    val latencyConfig by viewModel.latencyConfig.collectAsState()
    val preferredTransport by viewModel.preferredTransport.collectAsState()
    val maxClients by viewModel.maxClients.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val enableAutoDiscovery by viewModel.enableAutoDiscovery.collectAsState()
    val enableDebugLogging by viewModel.enableDebugLogging.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
    
    var showAdvancedSettings by remember { mutableStateOf(false) }


    Scaffold(
        containerColor = DuoBackground,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "SETTINGS", 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = DuoTextPrimary
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            TablerIcons.ArrowLeft, 
                            contentDescription = "Back",
                            tint = DuoTextSecondary
                        )
                    }
                },
                actions = {
                    if (hasUnsavedChanges) {
                        IconButton(onClick = { viewModel.saveSettings() }) {
                            Icon(
                                TablerIcons.DeviceFloppy, 
                                contentDescription = "Save",
                                tint = DuoGreen
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.resetToDefaults() }) {
                        Icon(
                            TablerIcons.Refresh, 
                            contentDescription = "Reset to defaults",
                            tint = DuoTextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DuoBackground
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            // Audio Settings Section
            item {
                SettingsSectionCard(
                    title = "AUDIO SETTINGS",
                    icon = TablerIcons.Music
                ) {
                    // Latency Profile (Most Important Setting)
                    LatencyProfileSettings(
                        currentProfile = latencyProfile,
                        onProfileChange = { viewModel.setLatencyProfile(it) }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = DuoSurfaceHighlight)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    AudioQualitySettings(
                        bitrate = audioQuality.bitrate,
                        encoding = audioQuality.encoding.name,
                        sampleRate = audioQuality.sampleRate,
                        onBitrateChange = { newBitrate ->
                            viewModel.setAudioQuality(audioQuality.copy(bitrate = newBitrate))
                        },
                        onEncodingChange = { newEncoding ->
                            val encoding = try { 
                                io.github.gauravyad69.speakershare.data.model.AudioEncoding.valueOf(newEncoding) 
                            } catch (e: Exception) { 
                                io.github.gauravyad69.speakershare.data.model.AudioEncoding.AAC 
                            }
                            viewModel.setAudioQuality(audioQuality.copy(encoding = encoding))
                        },
                        onSampleRateChange = { newRate ->
                            viewModel.setAudioQuality(audioQuality.copy(sampleRate = newRate))
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    BufferSettings(
                        bufferSize = bufferSize,
                        latencyMode = when {
                            bufferSize <= 512 -> "LOW"
                            bufferSize <= 2048 -> "BALANCED"
                            else -> "HIGH_QUALITY"
                        },
                        onBufferSizeChange = { viewModel.setBufferSize(it) },
                        onLatencyModeChange = { mode ->
                            val size = when (mode) {
                                "LOW" -> 512
                                "BALANCED" -> 1024
                                "HIGH_QUALITY" -> 4096
                                else -> 1024
                            }
                            viewModel.setBufferSize(size)
                        }
                    )
                }
            }

            // Network Settings Section
            item {
                SettingsSectionCard(
                    title = "NETWORK SETTINGS",
                    icon = TablerIcons.Wifi
                ) {
                    NetworkTransportSettings(
                        preferredTransport = preferredTransport.name,
                        fallbackEnabled = true,
                        onTransportChange = { transport ->
                            val streamTransport = try {
                                io.github.gauravyad69.speakershare.data.model.StreamTransport.valueOf(transport)
                            } catch (e: Exception) {
                                io.github.gauravyad69.speakershare.data.model.StreamTransport.WEBRTC
                            }
                            viewModel.setPreferredTransport(streamTransport)
                        },
                        onFallbackChange = { /* TODO: Add fallback setting */ }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    SwitchSetting(
                        title = "Enable Auto Discovery",
                        subtitle = "Automatically discover hosts on the network",
                        checked = enableAutoDiscovery,
                        onCheckedChange = { viewModel.setEnableAutoDiscovery(it) }
                    )
                }
            }

            // Host Settings Section
            item {
                SettingsSectionCard(
                    title = "HOST SETTINGS",
                    icon = TablerIcons.Server
                ) {
                    HostLimitsSettings(
                        maxClients = maxClients,
                        requiresPassword = false,
                        onMaxClientsChange = { viewModel.setMaxClients(it) },
                        onPasswordRequiredChange = { /* TODO: Add password setting */ }
                    )
                }
            }

            // UI & Behavior Section
            item {
                SettingsSectionCard(
                    title = "INTERFACE & BEHAVIOR",
                    icon = TablerIcons.Adjustments
                ) {
                    SwitchSetting(
                        title = "Keep Screen On",
                        subtitle = "Prevent screen from sleeping during broadcast",
                        checked = keepScreenOn,
                        onCheckedChange = { viewModel.setKeepScreenOn(it) }
                    )
                }
            }

            // Advanced Settings (Collapsible)
            item {
                AdvancedSettingsSection(
                    isExpanded = showAdvancedSettings,
                    onToggleExpanded = { showAdvancedSettings = !showAdvancedSettings },
                    settings = settings,
                    viewModel = viewModel
                )
            }

            // About Section
            item {
                SettingsSectionCard(
                    title = "ABOUT",
                    icon = TablerIcons.InfoCircle
                ) {
                    AboutSection(
                        onExportSettings = { viewModel.exportSettings() },
                        onImportSettings = { viewModel.importSettings() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = DuoTextSecondary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = DuoTextSecondary
                )
            }
            
            content()
        }
    }
}

@Composable
private fun LatencyProfileSettings(
    currentProfile: io.github.gauravyad69.speakershare.data.model.LatencyProfile,
    onProfileChange: (io.github.gauravyad69.speakershare.data.model.LatencyProfile) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Latency Profile",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = DuoTextPrimary
        )
        
        Text(
            text = "Choose a preset that balances latency vs audio quality",
            style = MaterialTheme.typography.bodySmall,
            color = DuoTextSecondary
        )
        
        val profiles = listOf(
            Triple(
                io.github.gauravyad69.speakershare.data.model.LatencyProfile.NO_LATENCY,
                "⚡ No Latency (~20-50ms)",
                "Raw PCM streaming. May cause glitches on poor networks. Uses ~1.4 Mbps."
            ),
            Triple(
                io.github.gauravyad69.speakershare.data.model.LatencyProfile.LOW_LATENCY,
                "🚀 Low Latency (~50-100ms)",
                "Minimal buffering with AAC. Good for real-time scenarios."
            ),
            Triple(
                io.github.gauravyad69.speakershare.data.model.LatencyProfile.BALANCED,
                "⚖️ Balanced (~100-200ms)",
                "Recommended. Good trade-off between latency and stability."
            ),
            Triple(
                io.github.gauravyad69.speakershare.data.model.LatencyProfile.HIGH_QUALITY,
                "🎵 High Quality (~200-500ms)",
                "Maximum stability and quality. Best for poor networks."
            )
        )
        
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            profiles.forEach { (profile, title, description) ->
                val isSelected = currentProfile == profile
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            onClick = { onProfileChange(profile) },
                            role = Role.RadioButton
                        ),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) 
                            DuoBlue.copy(alpha = 0.1f)
                        else 
                            DuoSurface
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        2.dp, 
                        if (isSelected) DuoBlue else DuoSurfaceHighlight
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null, // handled by selectable
                            colors = RadioButtonDefaults.colors(
                                selectedColor = DuoBlue,
                                unselectedColor = DuoTextSecondary
                            )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = DuoTextPrimary
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = DuoTextSecondary
                            )
                        }
                    }
                }
            }
        }
        
        // Warning for NO_LATENCY mode
        if (currentProfile == io.github.gauravyad69.speakershare.data.model.LatencyProfile.NO_LATENCY) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = DuoRed.copy(alpha = 0.1f)
                ),
                border = androidx.compose.foundation.BorderStroke(2.dp, DuoRed)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        TablerIcons.AlertTriangle,
                        contentDescription = null,
                        tint = DuoRed
                    )
                    Text(
                        text = "No Latency mode uses raw PCM audio which requires high bandwidth (~1.4 Mbps) and may cause audio glitches. Only use on excellent WiFi connections.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoRed
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioQualitySettings(
    bitrate: Int,
    encoding: String,
    sampleRate: Int,
    onBitrateChange: (Int) -> Unit,
    onEncodingChange: (String) -> Unit,
    onSampleRateChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Bitrate Slider
        SliderSetting(
            title = "Audio Bitrate",
            value = bitrate.toFloat(),
            valueRange = 64f..320f,
            steps = 7, // 64, 96, 128, 160, 192, 256, 320
            valueText = "${bitrate}kbps",
            onValueChange = { onBitrateChange(it.toInt()) }
        )
        
        // Encoding Selection
        DropdownSetting(
            title = "Audio Encoding",
            selectedValue = encoding,
            options = listOf("AAC", "OPUS", "PCM"),
            onSelectionChange = onEncodingChange
        )
        
        // Sample Rate Selection
        DropdownSetting(
            title = "Sample Rate",
            selectedValue = "${sampleRate}Hz",
            options = listOf("22050Hz", "44100Hz", "48000Hz"),
            onSelectionChange = { selected ->
                val rate = selected.removeSuffix("Hz").toInt()
                onSampleRateChange(rate)
            }
        )
    }
}

@Composable
private fun BufferSettings(
    bufferSize: Int,
    latencyMode: String,
    onBufferSizeChange: (Int) -> Unit,
    onLatencyModeChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SliderSetting(
            title = "Buffer Size",
            value = bufferSize.toFloat(),
            valueRange = 256f..8192f,
            steps = 5, // 256, 512, 1024, 2048, 4096, 8192
            valueText = "${bufferSize} samples",
            onValueChange = { onBufferSizeChange(nearestPowerOf2(it.toInt())) }
        )
        
        RadioGroupSetting(
            title = "Latency Mode",
            options = listOf("LOW", "BALANCED", "HIGH_QUALITY"),
            selectedOption = latencyMode,
            onOptionSelected = onLatencyModeChange,
            optionLabels = mapOf(
                "LOW" to "Low Latency",
                "BALANCED" to "Balanced",
                "HIGH_QUALITY" to "High Quality"
            )
        )
    }
}

@Composable
private fun NetworkTransportSettings(
    preferredTransport: String,
    fallbackEnabled: Boolean,
    onTransportChange: (String) -> Unit,
    onFallbackChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioGroupSetting(
            title = "Preferred Transport",
            options = listOf("WEBRTC", "UDP", "AUTO"),
            selectedOption = preferredTransport,
            onOptionSelected = onTransportChange,
            optionLabels = mapOf(
                "WEBRTC" to "WebRTC (Best quality)",
                "UDP" to "UDP (Lower latency)",
                "AUTO" to "Auto-select"
            )
        )
        
        SwitchSetting(
            title = "Enable Fallback Transport",
            subtitle = "Automatically switch to UDP if WebRTC fails",
            checked = fallbackEnabled,
            onCheckedChange = onFallbackChange
        )
    }
}

@Composable
private fun NetworkTimeoutSettings(
    connectionTimeout: Long,
    discoveryTimeout: Long,
    onConnectionTimeoutChange: (Long) -> Unit,
    onDiscoveryTimeoutChange: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SliderSetting(
            title = "Connection Timeout",
            value = (connectionTimeout / 1000).toFloat(),
            valueRange = 5f..30f,
            steps = 4, // 5s, 10s, 15s, 20s, 30s
            valueText = "${connectionTimeout / 1000}s",
            onValueChange = { onConnectionTimeoutChange((it * 1000).toLong()) }
        )
        
        SliderSetting(
            title = "Discovery Timeout",
            value = (discoveryTimeout / 1000).toFloat(),
            valueRange = 3f..15f,
            steps = 3, // 3s, 5s, 10s, 15s
            valueText = "${discoveryTimeout / 1000}s",
            onValueChange = { onDiscoveryTimeoutChange((it * 1000).toLong()) }
        )
    }
}

@Composable
private fun HostLimitsSettings(
    maxClients: Int,
    requiresPassword: Boolean,
    onMaxClientsChange: (Int) -> Unit,
    onPasswordRequiredChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SliderSetting(
            title = "Max Clients",
            value = if (maxClients == 0) 21f else maxClients.toFloat(),
            valueRange = 1f..21f,
            steps = 19, // 1-20, plus unlimited (21 = unlimited)
            valueText = if (maxClients == 0) "Unlimited" else "$maxClients",
            onValueChange = { 
                val value = it.toInt()
                onMaxClientsChange(if (value == 21) 0 else value)
            }
        )
        
        SwitchSetting(
            title = "Require Password",
            subtitle = "Clients must enter a password to connect",
            checked = requiresPassword,
            onCheckedChange = onPasswordRequiredChange
        )
    }
}

@Composable
private fun PortSettings(
    httpPort: Int,
    udpPort: Int,
    webrtcPort: Int,
    onHttpPortChange: (Int) -> Unit,
    onUdpPortChange: (Int) -> Unit,
    onWebrtcPortChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberInputSetting(
            title = "HTTP Port",
            value = httpPort,
            range = 1024..65535,
            onValueChange = onHttpPortChange
        )
        
        NumberInputSetting(
            title = "UDP Port",
            value = udpPort,
            range = 1024..65535,
            onValueChange = onUdpPortChange
        )
        
        NumberInputSetting(
            title = "WebRTC Port",
            value = webrtcPort,
            range = 1024..65535,
            onValueChange = onWebrtcPortChange
        )
    }
}

@Composable
private fun UISettings(
    keepScreenOn: Boolean,
    showNotifications: Boolean,
    vibrateOnEvents: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onShowNotificationsChange: (Boolean) -> Unit,
    onVibrateOnEventsChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchSetting(
            title = "Keep Screen On",
            subtitle = "Prevent screen from sleeping during broadcast",
            checked = keepScreenOn,
            onCheckedChange = onKeepScreenOnChange
        )
        
        SwitchSetting(
            title = "Show Notifications",
            subtitle = "Display status in notification bar",
            checked = showNotifications,
            onCheckedChange = onShowNotificationsChange
        )
        
        SwitchSetting(
            title = "Vibrate on Events",
            subtitle = "Vibrate when clients connect/disconnect",
            checked = vibrateOnEvents,
            onCheckedChange = onVibrateOnEventsChange
        )
    }
}

@Composable
private fun AutoBehaviorSettings(
    autoReconnect: Boolean,
    startInBackground: Boolean,
    onAutoReconnectChange: (Boolean) -> Unit,
    onStartInBackgroundChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchSetting(
            title = "Auto Reconnect",
            subtitle = "Automatically reconnect when connection is lost",
            checked = autoReconnect,
            onCheckedChange = onAutoReconnectChange
        )
        
        SwitchSetting(
            title = "Start in Background",
            subtitle = "Continue running when app is minimized",
            checked = startInBackground,
            onCheckedChange = onStartInBackgroundChange
        )
    }
}

@Composable
private fun AdvancedSettingsSection(
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    settings: UserSettings?,
    viewModel: SettingsViewModel
) {
    val enableDebugLogging by viewModel.enableDebugLogging.collectAsState()
    val autoStopTimer by viewModel.autoStopTimer.collectAsState()
    val audioSyncPositionTolerance by viewModel.audioSyncPositionTolerance.collectAsState()
    val audioSyncMinSeekInterval by viewModel.audioSyncMinSeekInterval.collectAsState()
    val videoSyncPositionTolerance by viewModel.videoSyncPositionTolerance.collectAsState()
    val videoSyncMinSeekInterval by viewModel.videoSyncMinSeekInterval.collectAsState()
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        TablerIcons.Settings,
                        contentDescription = null,
                        tint = DuoTextSecondary
                    )
                    Text(
                        text = "ADVANCED SETTINGS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DuoTextSecondary
                    )
                }
                
                Icon(
                    if (isExpanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                    contentDescription = null,
                    tint = DuoTextSecondary
                )
            }
            
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchSetting(
                        title = "Enable Debug Logging",
                        subtitle = "Log detailed network and audio information",
                        checked = enableDebugLogging,
                        onCheckedChange = { viewModel.setEnableDebugLogging(it) }
                    )
                    
                    SliderSetting(
                        title = "Auto-Stop Timer",
                        value = autoStopTimer.toFloat(),
                        valueRange = 0f..120f,
                        steps = 11,
                        valueText = if (autoStopTimer == 0) "Disabled" else "$autoStopTimer min",
                        onValueChange = { viewModel.setAutoStopTimer(it.toInt()) }
                    )
                    
                    HorizontalDivider(color = DuoSurfaceHighlight)
                    
                    Text(
                        text = "AUDIO SYNC SETTINGS",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = DuoTextSecondary
                    )
                    
                    Text(
                        text = "Configure audio playback synchronization. " +
                               "Audio requires tighter sync for a seamless experience.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoTextSecondary
                    )
                    
                    SliderSetting(
                        title = "Audio Position Tolerance",
                        value = audioSyncPositionTolerance.toFloat(),
                        valueRange = 50f..1000f,
                        steps = 18,
                        valueText = "${audioSyncPositionTolerance}ms",
                        onValueChange = { viewModel.setAudioSyncPositionTolerance(it.toInt()) }
                    )
                    
                    SliderSetting(
                        title = "Audio Seek Cooldown",
                        value = audioSyncMinSeekInterval.toFloat(),
                        valueRange = 500f..10000f,
                        steps = 18,
                        valueText = "${audioSyncMinSeekInterval}ms",
                        onValueChange = { viewModel.setAudioSyncMinSeekInterval(it.toInt()) }
                    )
                    
                    HorizontalDivider(color = DuoSurfaceHighlight)
                    
                    Text(
                        text = "VIDEO SYNC SETTINGS",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = DuoTextSecondary
                    )
                    
                    Text(
                        text = "Configure video playback synchronization. " +
                               "Video needs more relaxed sync due to buffering requirements.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoTextSecondary
                    )
                    
                    SliderSetting(
                        title = "Video Position Tolerance",
                        value = videoSyncPositionTolerance.toFloat(),
                        valueRange = 100f..2000f,
                        steps = 18,
                        valueText = "${videoSyncPositionTolerance}ms",
                        onValueChange = { viewModel.setVideoSyncPositionTolerance(it.toInt()) }
                    )
                    
                    SliderSetting(
                        title = "Video Seek Cooldown",
                        value = videoSyncMinSeekInterval.toFloat(),
                        valueRange = 1000f..15000f,
                        steps = 13,
                        valueText = "${videoSyncMinSeekInterval}ms",
                        onValueChange = { viewModel.setVideoSyncMinSeekInterval(it.toInt()) }
                    )
                    
                    HorizontalDivider(color = DuoSurfaceHighlight)
                    
                    Text(
                        text = "BUFFER CONFIGURATION",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = DuoTextSecondary
                    )
                    
                    Text(
                        text = "Smaller buffers = lower latency but may cause audio glitches. " +
                               "Larger buffers = more stable but higher latency.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSection(
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "SpeakerShare v1.0.0",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = DuoTextPrimary
        )
        
        Text(
            text = "Real-time audio broadcasting for Android",
            style = MaterialTheme.typography.bodySmall,
            color = DuoTextSecondary
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DuolingoButton(
                text = "EXPORT",
                onClick = onExportSettings,
                icon = TablerIcons.Upload,
                color = DuoSurfaceHighlight,
                shadowColor = DuoOutline,
                textColor = DuoTextSecondary,
                modifier = Modifier.weight(1f),
                height = 40.dp
            )
            
            DuolingoButton(
                text = "IMPORT",
                onClick = onImportSettings,
                icon = TablerIcons.Download,
                color = DuoSurfaceHighlight,
                shadowColor = DuoOutline,
                textColor = DuoTextSecondary,
                modifier = Modifier.weight(1f),
                height = 40.dp
            )
        }
    }
}

// Reusable Setting Components
@Composable
private fun SwitchSetting(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = DuoTextPrimary,
                fontWeight = FontWeight.Medium
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = DuoTextSecondary
                )
            }
        }
        
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DuoTextPrimary,
                checkedTrackColor = DuoGreen,
                uncheckedThumbColor = DuoTextSecondary,
                uncheckedTrackColor = DuoSurfaceHighlight,
                uncheckedBorderColor = DuoSurfaceHighlight
            )
        )
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = DuoTextPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = DuoBlue,
                fontWeight = FontWeight.Bold
            )
        }
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = DuoBlue,
                activeTrackColor = DuoBlue,
                inactiveTrackColor = DuoSurfaceHighlight,
                activeTickColor = DuoBlue,
                inactiveTickColor = DuoSurfaceHighlight
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(
    title: String,
    selectedValue: String,
    options: List<String>,
    onSelectionChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = DuoTextPrimary,
            fontWeight = FontWeight.Medium
        )
        
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selectedValue,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DuoBlue,
                    unfocusedBorderColor = DuoSurfaceHighlight,
                    focusedTextColor = DuoTextPrimary,
                    unfocusedTextColor = DuoTextPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = DuoSurface
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, color = DuoTextPrimary) },
                        onClick = {
                            onSelectionChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioGroupSetting(
    title: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    optionLabels: Map<String, String> = emptyMap()
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = DuoTextPrimary,
            fontWeight = FontWeight.Medium
        )
        
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (option == selectedOption),
                            onClick = { onOptionSelected(option) },
                            role = Role.RadioButton
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (option == selectedOption),
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = DuoBlue,
                            unselectedColor = DuoTextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = optionLabels[option] ?: option,
                        style = MaterialTheme.typography.bodySmall,
                        color = DuoTextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberInputSetting(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    var textValue by remember(value) { mutableStateOf(value.toString()) }
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = DuoTextPrimary,
            fontWeight = FontWeight.Medium
        )
        
        OutlinedTextField(
            value = textValue,
            onValueChange = { newValue ->
                textValue = newValue
                val intValue = newValue.toIntOrNull()
                if (intValue != null && intValue in range) {
                    onValueChange(intValue)
                }
            },
            placeholder = { Text("${range.first}-${range.last}", color = DuoTextDisabled) },
            singleLine = true,
            suffix = {
                Text(
                    text = "(${range.first}-${range.last})",
                    style = MaterialTheme.typography.bodySmall,
                    color = DuoTextSecondary
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DuoBlue,
                unfocusedBorderColor = DuoSurfaceHighlight,
                focusedTextColor = DuoTextPrimary,
                unfocusedTextColor = DuoTextPrimary
            ),
            shape = RoundedCornerShape(12.dp)
        )
    }
}

// Helper functions
private fun nearestPowerOf2(value: Int): Int {
    var n = 1
    while (n < value) n *= 2
    return if (kotlin.math.abs(n - value) < kotlin.math.abs(n / 2 - value)) n else n / 2
}