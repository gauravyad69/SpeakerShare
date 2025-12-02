package io.github.gauravyad69.speakershare.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import io.github.gauravyad69.speakershare.ui.screens.*
import io.github.gauravyad69.speakershare.ui.screens.synced.SyncedFilePlayerScreen

/**
 * Navigation destinations for the app
 */
sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : Screen(
        route = "home",
        title = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    )
    
    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )
    
    // Sub-screens (not in bottom nav)
    data object Host : Screen(
        route = "host",
        title = "Host",
        selectedIcon = Icons.Filled.Podcasts,
        unselectedIcon = Icons.Outlined.Podcasts
    )
    
    data object Discovery : Screen(
        route = "discovery",
        title = "Discovery",
        selectedIcon = Icons.Filled.Search,
        unselectedIcon = Icons.Outlined.Search
    )
    
    data object Client : Screen(
        route = "client/{ip}/{port}/{name}",
        title = "Client",
        selectedIcon = Icons.Filled.Headphones,
        unselectedIcon = Icons.Outlined.Headphones
    ) {
        fun createRoute(ip: String, port: Int, name: String) = "client/$ip/$port/$name"
    }
    
    data object ClientTransfer : Screen(
        route = "client_transfer/{ip}/{port}/{name}",
        title = "Client",
        selectedIcon = Icons.Filled.Headphones,
        unselectedIcon = Icons.Outlined.Headphones
    ) {
        fun createRoute(ip: String, port: Int, name: String) = "client_transfer/$ip/$port/$name"
    }
    
    data object Clients : Screen(
        route = "clients",
        title = "Connected Clients",
        selectedIcon = Icons.Filled.People,
        unselectedIcon = Icons.Outlined.People
    )
    
    data object HostAutoStart : Screen(
        route = "host/autostart",
        title = "Host",
        selectedIcon = Icons.Filled.Podcasts,
        unselectedIcon = Icons.Outlined.Podcasts
    )
    
    data object AudioFilePlayer : Screen(
        route = "file_player/audio",
        title = "Audio Player",
        selectedIcon = Icons.Filled.MusicNote,
        unselectedIcon = Icons.Outlined.MusicNote
    )

    data object VideoFilePlayer : Screen(
        route = "file_player/video",
        title = "Video Player",
        selectedIcon = Icons.Filled.VideoLibrary,
        unselectedIcon = Icons.Outlined.VideoLibrary
    )
    
    // Synced File Player routes
    data object SyncedAudioPlayer : Screen(
        route = "synced_player/audio",
        title = "Synced Audio",
        selectedIcon = Icons.Filled.MusicNote,
        unselectedIcon = Icons.Outlined.MusicNote
    )
    
    data object SyncedVideoPlayer : Screen(
        route = "synced_player/video",
        title = "Synced Video",
        selectedIcon = Icons.Filled.VideoLibrary,
        unselectedIcon = Icons.Outlined.VideoLibrary
    )
}// Items to show in bottom navigation
val bottomNavItems = listOf(Screen.Home, Screen.Settings)

@Composable
fun AppBottomNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    // Only show bottom nav on main screens
    val showBottomNav = currentRoute in listOf(Screen.Home.route, Screen.Settings.route)
    
    AnimatedVisibility(
        visible = showBottomNav,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        NavigationBar(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = NavigationBarDefaults.Elevation
        ) {
            bottomNavItems.forEach { screen ->
                val selected = currentRoute == screen.route
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                            contentDescription = screen.title
                        )
                    },
                    label = { Text(screen.title) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier,
        enterTransition = {
            fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                initialOffsetX = { 100 },
                animationSpec = tween(300)
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(300))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300))
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
                targetOffsetX = { 100 },
                animationSpec = tween(300)
            )
        }
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onHostModeSelected = { navController.navigate(Screen.Host.route) },
                onClientModeSelected = { navController.navigate(Screen.Discovery.route) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onAudioFilePlayerSelected = { navController.navigate(Screen.AudioFilePlayer.route) },
                onVideoFilePlayerSelected = { navController.navigate(Screen.VideoFilePlayer.route) },
                onSyncedAudioPlayerSelected = { navController.navigate(Screen.SyncedAudioPlayer.route) },
                onSyncedVideoPlayerSelected = { navController.navigate(Screen.SyncedVideoPlayer.route) }
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Host.route) {
            HostScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToClients = { navController.navigate(Screen.Clients.route) },
                onBecomeClient = { ip, port, hostName ->
                    navController.navigate(Screen.ClientTransfer.createRoute(ip, port, hostName)) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                autoStart = true
            )
        }
        
        composable(Screen.HostAutoStart.route) {
            HostScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToClients = { navController.navigate(Screen.Clients.route) },
                onBecomeClient = { ip, port, hostName ->
                    navController.navigate(Screen.ClientTransfer.createRoute(ip, port, hostName)) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                autoStart = true
            )
        }
        
        composable(Screen.Discovery.route) {
            DiscoveryScreen(
                onNavigateBack = { navController.popBackStack() },
                onHostSelected = { host ->
                    navController.navigate(Screen.Client.createRoute(host.ipAddress, host.port, host.serviceName))
                }
            )
        }
        
        composable(
            route = Screen.Client.route,
            arguments = listOf(
                navArgument("ip") { type = NavType.StringType },
                navArgument("port") { type = NavType.IntType },
                navArgument("name") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val ip = backStackEntry.arguments?.getString("ip") ?: ""
            val port = backStackEntry.arguments?.getInt("port") ?: 0
            val name = backStackEntry.arguments?.getString("name") ?: ""
            
            ClientScreen(
                onNavigateBack = { navController.popBackStack() },
                onBecomeHost = {
                    navController.navigate(Screen.HostAutoStart.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                initialHostIp = ip,
                initialHostPort = port,
                initialHostName = name
            )
        }
        
        composable(
            route = Screen.ClientTransfer.route,
            arguments = listOf(
                navArgument("ip") { type = NavType.StringType },
                navArgument("port") { type = NavType.IntType },
                navArgument("name") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val ip = backStackEntry.arguments?.getString("ip") ?: ""
            val port = backStackEntry.arguments?.getInt("port") ?: 0
            val name = backStackEntry.arguments?.getString("name") ?: ""
            
            ClientScreen(
                onNavigateBack = { navController.popBackStack() },
                onBecomeHost = {
                    navController.navigate(Screen.HostAutoStart.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                initialHostIp = ip,
                initialHostPort = port,
                initialHostName = name,
                isTransferReconnect = true
            )
        }
        
        composable("client") {
            ClientScreen(
                onNavigateBack = { navController.popBackStack() },
                onBecomeHost = {
                    navController.navigate(Screen.HostAutoStart.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                }
            )
        }
        
        composable(Screen.Clients.route) {
            ClientsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // File Player routes
        composable(Screen.AudioFilePlayer.route) {
            FilePlayerScreen(
                mode = FilePlayerMode.AUDIO,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.VideoFilePlayer.route) {
            FilePlayerScreen(
                mode = FilePlayerMode.VIDEO,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Synced File Player routes
        composable(Screen.SyncedAudioPlayer.route) {
            SyncedFilePlayerScreen(
                mediaType = "audio",
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.SyncedVideoPlayer.route) {
            SyncedFilePlayerScreen(
                mediaType = "video",
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
