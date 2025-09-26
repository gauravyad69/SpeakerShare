package io.github.gauravyad69.speakershare.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.gauravyad69.speakershare.data.models.AppSettings
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
    val settings by viewModel.settings.collectAsState()
    var showAdvancedSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.resetToDefaults() }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Reset to defaults")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            // Audio Settings Section
            item {
                SettingsSectionCard(
                    title = "Audio Settings",
                    icon = Icons.Default.AudioFile
                ) {
                    AudioQualitySettings(
                        bitrate = settings.audioBitrate,
                        encoding = settings.audioEncoding,
                        sampleRate = settings.audioSampleRate,
                        onBitrateChange = { viewModel.updateAudioBitrate(it) },
                        onEncodingChange = { viewModel.updateAudioEncoding(it) },
                        onSampleRateChange = { viewModel.updateAudioSampleRate(it) }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    BufferSettings(
                        bufferSize = settings.audioBufferSize,
                        latencyMode = settings.latencyMode,
                        onBufferSizeChange = { viewModel.updateAudioBufferSize(it) },
                        onLatencyModeChange = { viewModel.updateLatencyMode(it) }
                    )
                }
            }

            // Network Settings Section
            item {
                SettingsSectionCard(
                    title = "Network Settings",
                    icon = Icons.Default.NetworkWifi
                ) {
                    NetworkTransportSettings(
                        preferredTransport = settings.preferredTransport,
                        fallbackEnabled = settings.enableTransportFallback,
                        onTransportChange = { viewModel.updatePreferredTransport(it) },
                        onFallbackChange = { viewModel.updateTransportFallback(it) }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    NetworkTimeoutSettings(
                        connectionTimeout = settings.connectionTimeoutMs,
                        discoveryTimeout = settings.discoveryTimeoutMs,
                        onConnectionTimeoutChange = { viewModel.updateConnectionTimeout(it) },
                        onDiscoveryTimeoutChange = { viewModel.updateDiscoveryTimeout(it) }
                    )
                }
            }

            // Host Settings Section
            item {
                SettingsSectionCard(
                    title = "Host Settings",
                    icon = Icons.Default.Router
                ) {
                    HostLimitsSettings(
                        maxClients = settings.maxClients,
                        requiresPassword = settings.requiresPassword,
                        onMaxClientsChange = { viewModel.updateMaxClients(it) },
                        onPasswordRequiredChange = { viewModel.updatePasswordRequired(it) }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    PortSettings(
                        httpPort = settings.httpPort,
                        udpPort = settings.udpPort,
                        webrtcPort = settings.webrtcPort,
                        onHttpPortChange = { viewModel.updateHttpPort(it) },
                        onUdpPortChange = { viewModel.updateUdpPort(it) },
                        onWebrtcPortChange = { viewModel.updateWebrtcPort(it) }
                    )
                }
            }

            // UI & Behavior Section
            item {
                SettingsSectionCard(
                    title = "Interface & Behavior",
                    icon = Icons.Default.Tune
                ) {
                    UISettings(
                        keepScreenOn = settings.keepScreenOn,
                        showNotifications = settings.showNotifications,
                        vibrateOnEvents = settings.vibrateOnEvents,
                        onKeepScreenOnChange = { viewModel.updateKeepScreenOn(it) },
                        onShowNotificationsChange = { viewModel.updateShowNotifications(it) },
                        onVibrateOnEventsChange = { viewModel.updateVibrateOnEvents(it) }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    AutoBehaviorSettings(
                        autoReconnect = settings.autoReconnect,
                        startInBackground = settings.startInBackground,
                        onAutoReconnectChange = { viewModel.updateAutoReconnect(it) },
                        onStartInBackgroundChange = { viewModel.updateStartInBackground(it) }
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
                    title = "About",
                    icon = Icons.Default.Info
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            content()
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
    settings: AppSettings,
    viewModel: SettingsViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        Icons.Default.Engineering,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Advanced Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchSetting(
                        title = "Enable Debug Logging",
                        subtitle = "Log detailed network and audio information",
                        checked = settings.enableDebugLogging,
                        onCheckedChange = { viewModel.updateDebugLogging(it) }
                    )
                    
                    SwitchSetting(
                        title = "Force Software Encoding",
                        subtitle = "Use software encoder instead of hardware",
                        checked = settings.forceSoftwareEncoding,
                        onCheckedChange = { viewModel.updateForceSoftwareEncoding(it) }
                    )
                    
                    NumberInputSetting(
                        title = "WebRTC ICE Timeout (ms)",
                        value = settings.webrtcIceTimeoutMs.toInt(),
                        range = 1000..30000,
                        onValueChange = { viewModel.updateWebrtcIceTimeout(it.toLong()) }
                    )
                    
                    SliderSetting(
                        title = "Discovery Scan Interval",
                        value = (settings.discoveryScanIntervalMs / 1000).toFloat(),
                        valueRange = 1f..10f,
                        steps = 8,
                        valueText = "${settings.discoveryScanIntervalMs / 1000}s",
                        onValueChange = { 
                            viewModel.updateDiscoveryScanInterval((it * 1000).toLong()) 
                        }
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
            fontWeight = FontWeight.Medium
        )
        
        Text(
            text = "Real-time audio broadcasting for Android",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onExportSettings,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Export")
            }
            
            OutlinedButton(
                onClick = onImportSettings,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Import")
            }
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
                style = MaterialTheme.typography.bodyMedium
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
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
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
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
            style = MaterialTheme.typography.bodyMedium
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
                modifier = Modifier.menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
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
            style = MaterialTheme.typography.bodyMedium
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
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = optionLabels[option] ?: option,
                        style = MaterialTheme.typography.bodySmall
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
            style = MaterialTheme.typography.bodyMedium
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
            placeholder = { Text("${range.first}-${range.last}") },
            singleLine = true,
            suffix = {
                Text(
                    text = "(${range.first}-${range.last})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

// Helper functions
private fun nearestPowerOf2(value: Int): Int {
    var n = 1
    while (n < value) n *= 2
    return if (kotlin.math.abs(n - value) < kotlin.math.abs(n / 2 - value)) n else n / 2
}