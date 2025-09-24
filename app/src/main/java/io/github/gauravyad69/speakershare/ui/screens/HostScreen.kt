package io.github.gauravyad69.speakershare.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.gauravyad69.speakershare.ui.theme.SpeakerShareTheme
import io.github.gauravyad69.speakershare.ui.viewmodels.HostViewModel
import io.github.gauravyad69.speakershare.data.model.AudioSource
import io.github.gauravyad69.speakershare.data.model.ClientConnection

/**
 * Host Screen - Main screen for host mode with broadcasting controls
 * Implements T047: Host screen with controls
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    onNavigateToClients: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HostViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top App Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.Default.ArrowBack, 
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                
                Text(
                    text = "Host Mode",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                
                IconButton(onClick = onNavigateToClients) {
                    Icon(
                        Icons.Default.People, 
                        contentDescription = "View Clients",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Broadcasting Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (uiState.isHosting) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (uiState.isHosting) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = "Status",
                        tint = if (uiState.isHosting) Color.Green else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = if (uiState.isHosting) "Broadcasting" else "Not Broadcasting",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                if (uiState.isHosting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${uiState.connectedClients.size} clients connected",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Controls Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Start/Stop Broadcasting Button
            Button(
                onClick = { 
                    if (uiState.isHosting) {
                        viewModel.stopHosting()
                    } else {
                        viewModel.startHosting()
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isHosting) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            ) {
                Icon(
                    if (uiState.isHosting) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.isHosting) "Stop" else "Start",
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            // Mute/Unmute Button
            Button(
                onClick = { viewModel.toggleMute() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isMuted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                ),
                enabled = uiState.isHosting
            ) {
                Icon(
                    if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.isMuted) "Unmute" else "Mute",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Audio Source Selection
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Audio Source",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        onClick = { viewModel.switchAudioSource(AudioSource.MICROPHONE) },
                        label = { Text("Microphone") },
                        selected = uiState.audioSource == AudioSource.MICROPHONE,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    
                    FilterChip(
                        onClick = { viewModel.switchAudioSource(AudioSource.SYSTEM_AUDIO) },
                        label = { Text("System Audio") },
                        selected = uiState.audioSource == AudioSource.SYSTEM_AUDIO,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Computer,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Network Information Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Network Information",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "IP Address:",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.networkInfo?.localIpAddress ?: "Unknown",
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Port:",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.networkInfo?.port?.toString() ?: "8080",
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Connected Clients Preview
        if (uiState.connectedClients.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Connected Clients",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        TextButton(onClick = onNavigateToClients) {
                            Text("View All")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    uiState.connectedClients.take(3).forEach { client ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Text(
                                text = client.clientName,
                                fontSize = 14.sp
                            )
                        }
                    }
                    
                    if (uiState.connectedClients.size > 3) {
                        Text(
                            text = "and ${uiState.connectedClients.size - 3} more...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HostScreenPreview() {
    SpeakerShareTheme {
        // Note: Preview will show without ViewModel data
        // Actual implementation will use HostViewModel
        Surface {
            Text(
                text = "Host Screen Preview\n(Requires ViewModel)",
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}