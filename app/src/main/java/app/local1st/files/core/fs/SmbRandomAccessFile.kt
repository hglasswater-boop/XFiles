package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionRepo
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Backend-neutral seekable read handle for one SMB file.
 *
 * Callers keep using this facade while the implementation can move from SMBJ to the Pure Rust SMB
 * engine behind [SmbRandomAccessBackends]. Positional I/O remains the public primitive.
 */
class SmbRandomAccessFile private constructor(
    private val handle: SmbRandomAccessHandle,
    private val playbackPriority: Boolean,
) : Closeable {
    private val closed = AtomicBoolean(false)

    init {
        if (playbackPriority) SmbPlaybackIoPriority.onPlaybackOpened()
    }

    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed.get()) { "SMB file is closed" }
        if (position < 0L || length <= 0) return -1
        if (playbackPriority) {
            SmbPlaybackIoPriority.onPlaybackRead()
        } else {
            SmbPlaybackIoPriority.awaitBackgroundTurn()
        }
        return handle.read(position, buffer, offset, length)
    }

    /**
     * Signals a material access-position discontinuity without serializing behind an in-flight read.
     * SMBJ treats this as a no-op; the Rust backend advances its generation and can send SMB2 CANCEL
     * while the old read is still waiting on the network.
     */
    fun seek(position: Long) {
        check(!closed.get()) { "SMB file is closed" }
        if (position >= 0L) handle.seek(position)
    }

    /** Optional speculative read hint; backends may ignore or preempt it. */
    fun prefetch(position: Long, length: Int) {
        check(!closed.get()) { "SMB file is closed" }
        if (position >= 0L && length > 0) handle.prefetch(position, length)
    }

    /** True when the selected backend already owns read-ahead/cache below the Kotlin boundary. */
    val ownsReadAhead: Boolean
        get() {
            check(!closed.get()) { "SMB file is closed" }
            return handle.ownsReadAhead
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                handle.close()
            } finally {
                if (playbackPriority) SmbPlaybackIoPriority.onPlaybackClosed()
            }
        }
    }

    companion object {
        /** Normal random-access work, including thumbnails and metadata extraction. */
        fun open(id: String, connections: SmbConnectionRepo): SmbRandomAccessFile =
            SmbRandomAccessFile(
                handle = SmbRandomAccessBackends.open(id, connections),
                playbackPriority = false,
            )

        /** Media playback path. Foreground reads temporarily take priority over thumbnail I/O. */
        fun openForPlayback(id: String, connections: SmbConnectionRepo): SmbRandomAccessFile =
            SmbRandomAccessFile(
                handle = SmbRandomAccessBackends.open(id, connections),
                playbackPriority = true,
            )
    }
}

/**
 * Cooperative SMB I/O priority between Media3 playback and background random-access consumers.
 *
 * Rust SMB makes storyboard extraction much faster, which also means it can issue enough remote I/O
 * to compete with the player. Background callers therefore yield only while a playback source has
 * read very recently. Once the player has filled its buffer, the short quiet window expires and
 * thumbnails continue at full speed.
 */
private object SmbPlaybackIoPriority {
    private const val PLAYBACK_QUIET_PERIOD_NANOS = 120L * 1_000_000L
    private const val MAX_BACKGROUND_SLEEP_NANOS = 40L * 1_000_000L

    private val activePlaybackSources = AtomicInteger(0)
    private val lastPlaybackReadNanos = AtomicLong(Long.MIN_VALUE)

    fun onPlaybackOpened() {
        activePlaybackSources.incrementAndGet()
        lastPlaybackReadNanos.set(System.nanoTime())
    }

    fun onPlaybackRead() {
        if (activePlaybackSources.get() > 0) {
            lastPlaybackReadNanos.set(System.nanoTime())
        }
    }

    fun onPlaybackClosed() {
        val remaining = activePlaybackSources.updateAndGet { current ->
            if (current > 0) current - 1 else 0
        }
        if (remaining == 0) lastPlaybackReadNanos.set(Long.MIN_VALUE)
    }

    fun awaitBackgroundTurn() {
        while (activePlaybackSources.get() > 0) {
            val lastRead = lastPlaybackReadNanos.get()
            if (lastRead == Long.MIN_VALUE) return
            val elapsed = System.nanoTime() - lastRead
            val waitNanos = PLAYBACK_QUIET_PERIOD_NANOS - elapsed
            if (waitNanos <= 0L) return

            try {
                TimeUnit.NANOSECONDS.sleep(minOf(waitNanos, MAX_BACKGROUND_SLEEP_NANOS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
