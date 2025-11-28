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
                onNavigateToClients = { navController.navigate("clients") },
                onBecomeClient = { ip, port, hostName ->
                    // Navigate to client mode connecting to the new host (with retry for transfer)
                    navController.navigate("client_transfer/$ip/$port/$hostName") {
                        popUpTo("mode_selection") { inclusive = false }
                    }
                },
                autoStart = true  // Always auto-start when entering host mode
            )
        }
        
        // Host screen with auto-start for transfer recipients
        composable("host/autostart") {
            HostScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToClients = { navController.navigate("clients") },
                onBecomeClient = { ip, port, hostName ->
                    navController.navigate("client_transfer/$ip/$port/$hostName") {
                        popUpTo("mode_selection") { inclusive = false }
                    }
                },
                autoStart = true
            )
        }
        
        composable("discovery") {
            DiscoveryScreen(
                onNavigateBack = { navController.popBackStack() },
                onHostSelected = { host -> 
                    navController.navigate("client/${host.ipAddress}/${host.port}/${host.serviceName}")
                }
            )
        }
        
        composable(
            "client/{ip}/{port}/{name}",
            arguments = listOf(
                androidx.navigation.navArgument("ip") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("port") { type = androidx.navigation.NavType.IntType },
                androidx.navigation.navArgument("name") { type = androidx.navigation.NavType.StringType }
            )
        ) { backStackEntry ->
            val ip = backStackEntry.arguments?.getString("ip") ?: ""
            val port = backStackEntry.arguments?.getInt("port") ?: 0
            val name = backStackEntry.arguments?.getString("name") ?: ""
            
            ClientScreen(
                onNavigateBack = { navController.popBackStack() },
                onBecomeHost = {
                    // Navigate to host screen with auto-start for transfer
                    navController.navigate("host/autostart") {
                        popUpTo("mode_selection") { inclusive = false }
                    }
                },
                initialHostIp = ip,
                initialHostPort = port,
                initialHostName = name
            )
        }
        
        // Client route for host transfer reconnection (with retry logic)
        composable(
            "client_transfer/{ip}/{port}/{name}",
            arguments = listOf(
                androidx.navigation.navArgument("ip") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("port") { type = androidx.navigation.NavType.IntType },
                androidx.navigation.navArgument("name") { type = androidx.navigation.NavType.StringType }
            )
        ) { backStackEntry ->
            val ip = backStackEntry.arguments?.getString("ip") ?: ""
            val port = backStackEntry.arguments?.getInt("port") ?: 0
            val name = backStackEntry.arguments?.getString("name") ?: ""
            
            ClientScreen(
                onNavigateBack = { navController.popBackStack() },
                onBecomeHost = {
                    navController.navigate("host/autostart") {
                        popUpTo("mode_selection") { inclusive = false }
                    }
                },
                initialHostIp = ip,
                initialHostPort = port,
                initialHostName = name,
                isTransferReconnect = true  // Enable retry logic for transfer
            )
        }
        
        composable("client") {
            ClientScreen(
                onNavigateBack = { navController.popBackStack() },
                onBecomeHost = {
                    navController.navigate("host/autostart") {
                        popUpTo("mode_selection") { inclusive = false }
                    }
                }
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