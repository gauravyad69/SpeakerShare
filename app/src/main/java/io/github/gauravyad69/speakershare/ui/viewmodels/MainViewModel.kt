package io.github.gauravyad69.speakershare.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.gauravyad69.speakershare.data.model.UserSettings
import io.github.gauravyad69.speakershare.data.repository.UserSettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main ViewModel for overall app state management and navigation.
 * Manages app mode selection, navigation state, and global app configuration.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository
) : ViewModel() {

    // App mode selection
    private val _appMode = MutableStateFlow<AppMode>(AppMode.ModeSelection)
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    // Navigation state
    private val _currentScreen = MutableStateFlow<Screen>(Screen.ModeSelection)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // User settings
    private val _userSettings = MutableStateFlow<UserSettings?>(null)
    val userSettings: StateFlow<UserSettings?> = _userSettings.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadUserSettings()
    }

    /**
     * Load user settings from repository
     */
    private fun loadUserSettings() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                userSettingsRepository.getUserSettings().collect { settings ->
                    _userSettings.value = settings
                }
            } catch (e: Exception) {
                _error.value = "Failed to load user settings: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Set app mode (Host or Client)
     */
    fun setAppMode(mode: AppMode) {
        _appMode.value = mode
        when (mode) {
            AppMode.Host -> navigateToScreen(Screen.Host)
            AppMode.Client -> navigateToScreen(Screen.Discovery)
            AppMode.ModeSelection -> navigateToScreen(Screen.ModeSelection)
        }
    }

    /**
     * Navigate to specific screen
     */
    fun navigateToScreen(screen: Screen) {
        _currentScreen.value = screen
    }

    /**
     * Navigate back to previous screen
     */
    fun navigateBack() {
        when (_currentScreen.value) {
            Screen.Host -> navigateToScreen(Screen.ModeSelection)
            Screen.Client -> navigateToScreen(Screen.Discovery)
            Screen.Discovery -> navigateToScreen(Screen.ModeSelection)
            Screen.Settings -> {
                // Return to previous screen based on app mode
                when (_appMode.value) {
                    AppMode.Host -> navigateToScreen(Screen.Host)
                    AppMode.Client -> navigateToScreen(Screen.Client)
                    AppMode.ModeSelection -> navigateToScreen(Screen.ModeSelection)
                }
            }
            Screen.ClientsManagement -> navigateToScreen(Screen.Host)
            Screen.ModeSelection -> {
                // Can't go back from mode selection - this is the root
            }
        }
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Update user settings
     */
    fun updateUserSettings(settings: UserSettings) {
        viewModelScope.launch {
            val result = userSettingsRepository.updateSettings(settings)
            result.onSuccess {
                _userSettings.value = settings
            }.onFailure { e ->
                _error.value = "Failed to update settings: ${e.message}"
            }
        }
    }

    /**
     * Reset app to initial state
     */
    fun resetApp() {
        _appMode.value = AppMode.ModeSelection
        _currentScreen.value = Screen.ModeSelection
        _error.value = null
    }
}

/**
 * App operating modes
 */
enum class AppMode {
    ModeSelection,  // Initial state - user selects mode
    Host,          // User is hosting audio
    Client         // User is connecting to a host
}

/**
 * App navigation screens
 */
enum class Screen {
    ModeSelection,     // Choose Host or Client mode
    Host,              // Host broadcasting screen
    Client,            // Client playback screen
    Discovery,         // Discover available hosts
    Settings,          // App settings
    ClientsManagement  // Manage connected clients (Host only)
}
