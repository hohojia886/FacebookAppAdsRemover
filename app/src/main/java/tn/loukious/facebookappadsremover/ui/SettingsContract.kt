package tn.loukious.facebookappadsremover.ui

import android.net.Uri

/**
 * UI State for MainActivity settings screen.
 */
data class SettingsUiState(
    val isServiceBound: Boolean = false,
    val toggleValues: Map<String, Boolean> = emptyMap(),
    val keywords: String = "",
    val isLauncherIconVisible: Boolean = true,
)

/**
 * User Intents (Actions) originating from the Settings UI.
 */
sealed interface SettingsUserIntent {
    data class ToggleChanged(val key: String, val checked: Boolean) : SettingsUserIntent
    data class KeywordsChanged(val keywords: String) : SettingsUserIntent
    data class LauncherIconToggleRequested(val show: Boolean) : SettingsUserIntent
    object ConfirmHideLauncherIcon : SettingsUserIntent
    object ExportSessionRequested : SettingsUserIntent
    object ImportLatestSessionRequested : SettingsUserIntent
    data class ImportSessionFilePicked(val uri: Uri?) : SettingsUserIntent
}

/**
 * One-shot side effects triggered by ViewModel for the UI to consume.
 */
sealed interface SettingsUiEffect {
    data class ShowToast(val message: String) : SettingsUiEffect
    object ShowHideLauncherIconConfirmDialog : SettingsUiEffect
}
