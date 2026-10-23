package vn.aquastream.tv

import org.junit.Assert.*
import org.junit.Test

class PlayerUiStateTest {
    @Test fun visibleOverlayOwnsNavigation() {
        val state = PlayerUiState()
        assertFalse(state.ownsNavigation)
        state.show(PlayerUiState.Mode.INFO, 1000, 5000)
        assertTrue(state.ownsNavigation)
        state.show(PlayerUiState.Mode.CONTROLS, 1000, 5000)
        assertTrue(state.ownsNavigation)
    }
    @Test fun settingsNeverAutoHide() {
        val state = PlayerUiState(); state.show(PlayerUiState.Mode.SETTINGS, 0, 5000)
        assertFalse(state.expire(100_000, true)); assertEquals(PlayerUiState.Mode.SETTINGS, state.mode)
    }
    @Test fun idleTimelineAutoHidesWithoutLeavingFocus() {
        val state = PlayerUiState(); state.show(PlayerUiState.Mode.SCRUB, 1000, 5000)
        assertFalse(state.expire(5999, true))
        assertTrue(state.expire(6000, true))
        assertEquals(PlayerUiState.Mode.WATCH, state.mode)
        assertFalse(state.ownsNavigation)
    }
    @Test fun timelineInputExtendsTimeoutAndPauseKeepsOsd() {
        val state = PlayerUiState(); state.show(PlayerUiState.Mode.SCRUB, 0, 5000)
        state.touch(4000, 5000)
        assertFalse(state.expire(5000, true))
        assertFalse(state.expire(8999, true))
        assertFalse(state.expire(9000, false))
        assertEquals(PlayerUiState.Mode.SCRUB, state.mode)
        assertTrue(state.expire(9000, true))
    }
    @Test fun pauseKeepsOsdAndInteractionExtendsDeadline() {
        val state = PlayerUiState(); state.show(PlayerUiState.Mode.INFO, 0, 5000)
        assertFalse(state.expire(9000, false))
        state.touch(9000, 5000)
        assertFalse(state.expire(13999, true)); assertTrue(state.expire(14000, true))
        assertEquals(PlayerUiState.Mode.WATCH, state.mode)
    }
}
