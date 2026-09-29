package tn.loukious.facebookappadsremover.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tn.loukious.facebookappadsremover.core.Settings

class SettingsContractTest {

    @Test
    fun testDefaultSettingsUiState() {
        val state = SettingsUiState()
        assertFalse(state.isServiceBound)
        assertTrue(state.toggleValues.isEmpty())
        assertEquals("", state.keywords)
        assertTrue(state.isLauncherIconVisible)
    }

    @Test
    fun testSettingsUiStateCopy() {
        val state = SettingsUiState().copy(
            isServiceBound = true,
            toggleValues = mapOf(Settings.ADS_NEWS_FEED to true),
            keywords = "crypto, giveaway",
            isLauncherIconVisible = false
        )
        assertTrue(state.isServiceBound)
        assertEquals(true, state.toggleValues[Settings.ADS_NEWS_FEED])
        assertEquals("crypto, giveaway", state.keywords)
        assertFalse(state.isLauncherIconVisible)
    }
}
