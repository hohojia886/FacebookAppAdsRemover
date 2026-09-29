package tn.loukious.facebookappadsremover.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tn.loukious.facebookappadsremover.core.SessionBackup
import tn.loukious.facebookappadsremover.core.Settings
import tn.loukious.facebookappadsremover.data.SessionRepository
import tn.loukious.facebookappadsremover.data.SessionResult
import tn.loukious.facebookappadsremover.data.SettingsRepository

/**
 * ViewModel managing settings state and handling UI intents following UDF principles.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository = SettingsRepository(),
    private val sessionRepository: SessionRepository = SessionRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<SettingsUiEffect>()
    val uiEffect: SharedFlow<SettingsUiEffect> = _uiEffect.asSharedFlow()

    fun initService(context: Context) {
        settingsRepository.registerService(context)
        _uiState.update { it.copy(isLauncherIconVisible = LauncherIcon.isVisible(context)) }

        viewModelScope.launch {
            settingsRepository.isServiceBound.collect { bound ->
                if (bound) {
                    loadSettingsFromRepository()
                } else {
                    _uiState.update { it.copy(isServiceBound = false) }
                }
            }
        }
    }

    private fun loadSettingsFromRepository() {
        val togglesMap = mutableMapOf<String, Boolean>()
        for (section in TOGGLE_SECTIONS) {
            for (toggle in section.toggles) {
                togglesMap[toggle.key] = settingsRepository.getBoolean(toggle.key, toggle.default)
            }
        }
        val keywords = settingsRepository.getString(Settings.FEED_KEYWORDS, "")
        _uiState.update {
            it.copy(
                isServiceBound = true,
                toggleValues = togglesMap,
                keywords = keywords,
            )
        }
    }

    fun processIntent(context: Context, intent: SettingsUserIntent) {
        when (intent) {
            is SettingsUserIntent.ToggleChanged -> {
                settingsRepository.setBoolean(intent.key, intent.checked)
                _uiState.update {
                    it.copy(toggleValues = it.toggleValues + (intent.key to intent.checked))
                }
            }
            is SettingsUserIntent.KeywordsChanged -> {
                settingsRepository.setString(Settings.FEED_KEYWORDS, intent.keywords)
                _uiState.update { it.copy(keywords = intent.keywords) }
            }
            is SettingsUserIntent.LauncherIconToggleRequested -> {
                if (intent.show) {
                    LauncherIcon.setVisible(context, true)
                    _uiState.update { it.copy(isLauncherIconVisible = true) }
                } else {
                    viewModelScope.launch {
                        _uiEffect.emit(SettingsUiEffect.ShowHideLauncherIconConfirmDialog)
                    }
                }
            }
            is SettingsUserIntent.ConfirmHideLauncherIcon -> {
                LauncherIcon.setVisible(context, false)
                _uiState.update { it.copy(isLauncherIconVisible = false) }
            }
            is SettingsUserIntent.ExportSessionRequested -> {
                handleSessionResult(
                    sessionRepository.sendSessionBroadcast(context, SessionBackup.ACTION_EXPORT)
                )
            }
            is SettingsUserIntent.ImportLatestSessionRequested -> {
                handleSessionResult(
                    sessionRepository.sendSessionBroadcast(context, SessionBackup.ACTION_IMPORT)
                )
            }
            is SettingsUserIntent.ImportSessionFilePicked -> {
                handleSessionResult(
                    sessionRepository.processPickedSessionFile(context, intent.uri)
                )
            }
        }
    }

    private fun handleSessionResult(result: SessionResult) {
        val message = when (result) {
            is SessionResult.Success -> result.message
            is SessionResult.Error -> result.message
        }
        viewModelScope.launch {
            _uiEffect.emit(SettingsUiEffect.ShowToast(message))
        }
    }
}
