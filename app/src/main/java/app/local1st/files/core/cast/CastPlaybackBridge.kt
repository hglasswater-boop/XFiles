package app.local1st.files.core.cast

/** Lightweight state shared with the foreground notification while remote playback is active. */
data class CastPlaybackNotificationState(
    val title: String,
    val playing: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

/** Transport operations owned by the active mobile Cast session. */
interface CastPlaybackNotificationController {
    fun togglePlayPause()
    fun seekBy(deltaMs: Long)
    fun previous()
    fun next()
}

/**
 * Flavor-neutral bridge between the common foreground service and the mobile-only Cast player.
 *
 * The service never owns playback. It forwards notification actions to the controller attached by
 * the mobile session manager, keeping one authoritative Player instance for UI and notification.
 */
object CastPlaybackBridge {
    private val lock = Any()

    @Volatile
    private var controller: CastPlaybackNotificationController? = null

    @Volatile
    private var state: CastPlaybackNotificationState? = null

    fun attach(
        owner: CastPlaybackNotificationController,
        playbackState: CastPlaybackNotificationState,
    ) {
        synchronized(lock) {
            controller = owner
            state = playbackState
        }
    }

    fun update(
        owner: CastPlaybackNotificationController,
        playbackState: CastPlaybackNotificationState,
    ) {
        synchronized(lock) {
            if (controller === owner) state = playbackState
        }
    }

    fun detach(owner: CastPlaybackNotificationController) {
        synchronized(lock) {
            if (controller !== owner) return
            controller = null
            state = null
        }
    }

    fun currentState(): CastPlaybackNotificationState? = state

    fun togglePlayPause() {
        controller?.togglePlayPause()
    }

    fun seekBy(deltaMs: Long) {
        controller?.seekBy(deltaMs)
    }

    fun previous() {
        controller?.previous()
    }

    fun next() {
        controller?.next()
    }
}
