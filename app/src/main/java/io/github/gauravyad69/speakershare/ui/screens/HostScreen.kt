package io.github.gauravyad69.speakershare.ui.screens

import android.app.Activity
import timber.log.Timber
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.DeviceSpeaker
import compose.icons.tablericons.InfoCircle
import compose.icons.tablericons.Microphone
import compose.icons.tablericons.PlayerStop
import compose.icons.tablericons.Settings
import compose.icons.tablericons.Share
import compose.icons.tablericons.Users
import compose.icons.tablericons.Headphones
import io.github.gauravyad69.speakershare.data.model.AudioSource
import io.github.gauravyad69.speakershare.data.model.ClientConnection
import io.github.gauravyad69.speakershare.ui.components.DuolingoButton
import io.github.gauravyad69.speakershare.ui.theme.*
import io.github.gauravyad69.speakershare.ui.viewmodels.HostViewModel
import io.github.gauravyad69.speakershare.ui.viewmodels.TransferStatus

/**
 * Modern Host Screen with Duolingo-inspired Dark UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    onNavigateToClients: () -> Unit,
    onNavigateBack: () -> Unit,
    onBecomeClient: (ip: String, port: Int, hostName: String) -> Unit = { _, _, _ -> },
    autoStart: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: HostViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val transferStatus by viewModel.transferStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mediaProjectionManager = remember { 
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager 
    }
    val scrollState = rememberScrollState()

    // Track which mode was requested when MediaProjection permission is granted
    var pendingAudioSourceMode by remember { mutableStateOf<AudioSource?>(null) }
    var showClientMenu by remember { mutableStateOf<String?>(null) }
    var showTransferConfirmDialog by remember { mutableStateOf<ClientConnection?>(null) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val requestedMode = pendingAudioSourceMode ?: AudioSource.SYSTEM_AUDIO
            Timber.d("MediaProjection permission granted for mode: $requestedMode")
            
            val initIntent = io.github.gauravyad69.speakershare.services.AudioForegroundService.initMediaProjection(
                context, result.resultCode, result.data!!
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(initIntent)
            } else {
                context.startService(initIntent)
            }
            
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val switchIntent = when (requestedMode) {
                    AudioSource.SCREEN_AND_AUDIO -> 
                        io.github.gauravyad69.speakershare.services.AudioForegroundService.switchToScreenAndAudio(context)
                    else -> 
                        io.github.gauravyad69.speakershare.services.AudioForegroundService.switchToSystemAudio(context)
                }
                context.startService(switchIntent)
                viewModel.updateAudioSourceState(requestedMode)
            }, 500)
        }
        pendingAudioSourceMode = null
    }
    
    // Auto-start hosting
    LaunchedEffect(autoStart) {
        if (autoStart && !uiState.isHosting) {
            viewModel.startHosting(hostName = android.os.Build.MODEL)
        }
    }
    
    // Handle becoming a client after transfer
    LaunchedEffect(transferStatus) {
        if (transferStatus is TransferStatus.BecomeClient) {
            val status = transferStatus as TransferStatus.BecomeClient
            onBecomeClient(status.newHostIp, status.newHostPort, status.newHostName)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DuoBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        TablerIcons.ArrowLeft,
                        contentDescription = "Back",
                        tint = DuoTextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "HOST MODE",
                        style = MaterialTheme.typography.labelLarge,
                        color = DuoTextSecondary
                    )
                    Text(
                        if (uiState.isHosting) "BROADCASTING" else "READY TO HOST",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (uiState.isHosting) DuoGreen else DuoTextPrimary
                    )
                }
                
                if (uiState.isHosting) {
                    IconButton(onClick = { viewModel.stopHosting() }) {
                        Icon(
                            TablerIcons.PlayerStop,
                            contentDescription = "Stop",
                            tint = DuoRed,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Status Card
            HostStatusCard(
                isStreaming = uiState.isHosting,
                clientCount = uiState.connectedClients.size,
                ipAddress = uiState.networkInfo?.localIpAddress ?: "Unknown",
                port = uiState.networkInfo?.port ?: 8080
            )
            
            // Controls
            if (!uiState.isHosting) {
                DuolingoButton(
                    text = "START HOSTING",
                    onClick = { viewModel.startHosting(hostName = android.os.Build.MODEL) },
                    icon = TablerIcons.Microphone,
                    color = DuoGreen,
                    shadowColor = DuoGreenShadow,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "AUDIO SOURCE",
                        style = MaterialTheme.typography.labelLarge,
                        color = DuoTextSecondary
                    )
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Mic Source
                        SourceButton(
                            text = "MIC",
                            icon = TablerIcons.Microphone,
                            isSelected = uiState.audioSource == AudioSource.MICROPHONE,
                            onClick = { viewModel.switchAudioSource(AudioSource.MICROPHONE) },
                            modifier = Modifier.weight(1f)
                        )
                        
                        // System Audio Source
                        SourceButton(
                            text = "SYSTEM",
                            icon = TablerIcons.DeviceSpeaker,
                            isSelected = uiState.audioSource == AudioSource.SYSTEM_AUDIO,
                            onClick = { 
                                pendingAudioSourceMode = AudioSource.SYSTEM_AUDIO
                                val intent = mediaProjectionManager?.createScreenCaptureIntent()
                                if (intent != null) {
                                    mediaProjectionLauncher.launch(intent)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    DuolingoButton(
                        text = "SCREEN & AUDIO",
                        onClick = {
                            pendingAudioSourceMode = AudioSource.SCREEN_AND_AUDIO
                            val intent = mediaProjectionManager?.createScreenCaptureIntent()
                            if (intent != null) {
                                mediaProjectionLauncher.launch(intent)
                            }
                        },
                        icon = TablerIcons.Share,
                        color = if (uiState.audioSource == AudioSource.SCREEN_AND_AUDIO) DuoPurple else DuoSurfaceHighlight,
                        shadowColor = if (uiState.audioSource == AudioSource.SCREEN_AND_AUDIO) DuoPurpleShadow else DuoOutline,
                        textColor = if (uiState.audioSource == AudioSource.SCREEN_AND_AUDIO) DuoTextPrimary else DuoTextSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Connected Clients
                Text(
                    "CONNECTED CLIENTS (${uiState.connectedClients.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = DuoTextSecondary
                )
                
                if (uiState.connectedClients.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DuoSurface),
                        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                TablerIcons.Users,
                                contentDescription = null,
                                tint = DuoTextDisabled,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Waiting for clients...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = DuoTextSecondary
                            )
                        }
                    }
                } else {
                    uiState.connectedClients.forEach { client ->
                        ClientItem(
                            client = client,
                            onTransferHost = { showTransferConfirmDialog = client }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
    
    // Transfer Confirmation Dialog
    if (showTransferConfirmDialog != null) {
        val client = showTransferConfirmDialog!!
        AlertDialog(
            onDismissRequest = { showTransferConfirmDialog = null },
            title = { Text("Transfer Host?", style = MaterialTheme.typography.titleLarge) },
            text = { 
                Text(
                    "Are you sure you want to transfer hosting duties to ${client.clientName}? You will become a client.",
                    style = MaterialTheme.typography.bodyMedium
                ) 
            },
            confirmButton = {
                DuolingoButton(
                    text = "TRANSFER",
                    onClick = {
                        viewModel.requestTransferHost(client.clientId)
                        showTransferConfirmDialog = null
                    },
                    color = DuoOrange,
                    shadowColor = DuoOrangeShadow,
                    height = 40.dp
                )
            },
            dismissButton = {
                TextButton(onClick = { showTransferConfirmDialog = null }) {
                    Text("CANCEL", color = DuoTextSecondary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DuoSurface,
            titleContentColor = DuoTextPrimary,
            textContentColor = DuoTextSecondary
        )
    }
}

@Composable
private fun HostStatusCard(
    isStreaming: Boolean,
    clientCount: Int,
    ipAddress: String,
    port: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isStreaming) DuoGreen.copy(alpha = 0.2f) else DuoTextDisabled.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    TablerIcons.Microphone,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = if (isStreaming) DuoGreen else DuoTextDisabled
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = if (isStreaming) "ON AIR" else "OFFLINE",
                style = MaterialTheme.typography.titleLarge,
                color = if (isStreaming) DuoGreen else DuoTextDisabled
            )
            
            if (isStreaming) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = DuoSurfaceHighlight,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "$ipAddress:$port",
                        style = MaterialTheme.typography.labelMedium,
                        color = DuoTextSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceButton(
    text: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    DuolingoButton(
        text = text,
        onClick = onClick,
        icon = icon,
        color = if (isSelected) DuoBlue else DuoSurfaceHighlight,
        shadowColor = if (isSelected) DuoBlueShadow else DuoOutline,
        textColor = if (isSelected) DuoTextPrimary else DuoTextSecondary,
        modifier = modifier
    )
}

@Composable
private fun ClientItem(
    client: ClientConnection,
    onTransferHost: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, DuoSurfaceHighlight)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DuoBlue.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    TablerIcons.Headphones,
                    contentDescription = null,
                    tint = DuoBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = client.clientName,
                    style = MaterialTheme.typography.titleSmall,
                    color = DuoTextPrimary
                )
                Text(
                    text = client.ipAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = DuoTextSecondary
                )
            }
            
            IconButton(onClick = onTransferHost) {
                Icon(
                    TablerIcons.Share,
                    contentDescription = "Transfer Host",
                    tint = DuoTextSecondary
                )
            }
        }
    }
}
