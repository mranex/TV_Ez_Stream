package vn.aquastream.tv

/** Visibility and input ownership are independent from ExoPlayer state.
 * WATCH = hidden (horizontal short seek); INFO/CONTROLS = visible row navigation;
 * SCRUB = timeline focus, entered by UP, never by a confirmation click.
 */
class PlayerUiState {
    enum class Mode { WATCH, INFO, CONTROLS, SCRUB, SETTINGS }
    enum class FocusRegion { PLAYBACK, ACTIONS, TIMELINE, TOP, SETTINGS }
    var mode = Mode.WATCH
        private set
    var deadline = 0L
        private set
    fun show(next: Mode, now: Long, timeoutMs: Long) { mode = next; touch(now, timeoutMs) }
    fun touch(now: Long, timeoutMs: Long) { deadline = now + timeoutMs }
    fun expire(now: Long, playing: Boolean): Boolean {
        if (playing && (mode == Mode.INFO || mode == Mode.CONTROLS || mode == Mode.SCRUB) && now >= deadline) {
            mode = Mode.WATCH; return true
        }
        return false
    }
    fun hide() { mode = Mode.WATCH }
    val ownsNavigation get() = mode == Mode.INFO || mode == Mode.CONTROLS || mode == Mode.SCRUB || mode == Mode.SETTINGS
}
