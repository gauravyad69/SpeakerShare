package io.github.gauravyad69.speakershare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.gauravyad69.speakershare.ui.screens.*
import io.github.gauravyad69.speakershare.ui.theme.SpeakerShareTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpeakerShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SpeakerShareApp()
                }
            }
        }
    }
}

@Composable
fun SpeakerShareApp() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = "mode_selection"
    ) {
        composable("mode_selection") {
            ModeSelectionScreen(
                onHostModeSelected = { navController.navigate("host") },
                onClientModeSelected = { navController.navigate("discovery") }
            )
        }
        
        composable("host") {
            HostScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToClients = { navController.navigate("clients") }
            )
        }
        
        composable("discovery") {
            DiscoveryScreen(
                onNavigateBack = { navController.popBackStack() },
                onHostSelected = { navController.navigate("client") }
            )
        }
        
        composable("client") {
            ClientScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable("clients") {
            ClientsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}