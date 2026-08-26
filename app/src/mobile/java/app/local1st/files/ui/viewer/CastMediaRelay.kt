package app.local1st.files.ui.viewer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import com.google.android.gms.cast.MediaQueueItem
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
internal class CastMediaRelay(
    private val context: Context,
    entries: List<XEntry>,
) : Closeable {
    private data class Source(
        val entry: XEntry,
        val uri: Uri,
        val size: Long,
        val mimeType: String,
        val token: String,
    )

    private val closed = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "xfiles-cast-relay").apply { isDaemon = true }
    }
    private val host = findLanIpv4(context)?.hostAddress
    private val server = if (host != null) {
        runCatching { ServerSocket(0, 32, InetAddress.getByName("0.0.0.0")) }.getOrNull()
    } else {
        null
    }

    private val sourcesById: Map<String, Source> = entries.mapNotNull { entry ->
        val uri = relaySourceUri(entry) ?: return@mapNotNull null
        Source(
            entry = entry,
            uri = uri,
            size = relaySize(context, entry, uri),
            mimeType = castMimeType(entry),
            token = UUID.randomUUID().toString().replace("-", ""),
        )
    }.associateBy { it.entry.id }
    private val sourcesByToken = sourcesById.values.associateBy(Source::token)

    private val wakeLock = if (server != null) {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XFiles:CastRelay")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    } else {
        null
    }

    init {
        server?.let { relayServer -> executor.execute { acceptLoop(relayServer) } }
    }

    fun urlFor(mediaId: String): Uri? {
        val source = sourcesById[mediaId] ?: return null
        val relayServer = server ?: return null
        val relayHost = host ?: return null
        if (closed.get()) return null
        return Uri.parse(
            "http://$relayHost:${relayServer.localPort}/media/${source.token}/${Uri.encode(source.entry.name)}",
        )
    }

    private fun acceptLoop(relayServer: ServerSocket) {
        while (!closed.get()) {
            val socket = try {
                relayServer.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (closed.get()) break
                continue
            }
            executor.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 30_000
            try {
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val requestLine = reader.readLine() ?: return
                val requestParts = requestLine.split(' ')
                if (requestParts.size < 2) return
                val method = requestParts[0].uppercase(Locale.US)
                val path = requestParts[1].substringBefore('?')
                if (method != "GET" && method != "HEAD") {
                    sendStatus(client, 405, "Method Not Allowed")
                    return
                }

                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                            line.substring(colon + 1).trim()
                    }
                }

                val segments = path.trim('/').split('/')
                if (segments.size < 2 || segments[0] != "media") {
                    sendStatus(client, 404, "Not Found")
                    return
                }
                val source = sourcesByToken[segments[1]]
                if (source == null) {
                    sendStatus(client, 404, "Not Found")
                    return
                }
                if (source.size < 0L) {
                    sendStatus(client, 503, "Media length unavailable")
                    return
                }

                val rangeHeader = headers["range"]
                val range = resolveRange(rangeHeader, source.size)
                if (rangeHeader != null && range == null) {
                    sendRangeNotSatisfiable(client, source.size)
                    return
                }
                val selection = range ?: ByteRange(
                    start = 0L,
                    endInclusive = (source.size - 1L).coerceAtLeast(-1L),
                    partial = false,
                )
                val bodyLength = if (selection.endInclusive >= selection.start) {
                    selection.endInclusive - selection.start + 1L
                } else {
                    0L
                }

                val out = client.getOutputStream()
                val status = if (selection.partial) "206 Partial Content" else "200 OK"
                val responseHeaders = buildString {
                    append("HTTP/1.1 ").append(status).append("\r\n")
                    append("Content-Type: ").append(source.mimeType).append("\r\n")
                    append("Content-Length: ").append(bodyLength).append("\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    if (selection.partial) {
                        append("Content-Range: bytes ")
                            .append(selection.start)
                            .append('-')
                            .append(selection.endInclusive)
                            .append('/')
                            .append(source.size)
                            .append("\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                out.write(responseHeaders.toByteArray(Charsets.US_ASCII))
                if (method == "HEAD" || bodyLength == 0L) {
                    out.flush()
                    return
                }

                val dataSource = DefaultDataSource.Factory(
                    context,
                    XFilesRemoteDataSource.Factory(),
                ).createDataSource()
                try {
                    dataSource.open(
                        DataSpec.Builder()
                            .setUri(source.uri)
                            .setPosition(selection.start)
                            .setLength(bodyLength)
                            .build(),
                    )
                    val buffer = ByteArray(128 * 1024)
                    var remaining = bodyLength
                    while (remaining > 0L && !closed.get()) {
                        val requested = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = dataSource.read(buffer, 0, requested)
                        if (read == C.RESULT_END_OF_INPUT) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                    out.flush()
                } finally {
                    runCatching { dataSource.close() }
                }
            } catch (_: IOException) {
                // Receiver disconnected or sought elsewhere. A new HTTP range request will follow.
            } catch (_: RuntimeException) {
                // Keep the relay alive for other playlist entries/range requests.
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        executor.shutdownNow()
    }

    private companion object {
        fun sendStatus(socket: Socket, code: Int, reason: String) {
            val body = "$code $reason\n".toByteArray(Charsets.UTF_8)
            runCatching {
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 $code $reason\r\n" +
                                "Content-Type: text/plain; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                    )
                    write(body)
                    flush()
                }
            }
        }

        fun sendRangeNotSatisfiable(socket: Socket, size: Long) {
            runCatching {
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                                "Content-Range: bytes */$size\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                    )
                    flush()
                }
            }
        }
    }
}

internal data class ByteRange(
    val start: Long,
    val endInclusive: Long,
    val partial: Boolean,
)

internal fun resolveRange(header: String?, size: Long): ByteRange? {
    if (size < 0L) return null
    if (header == null) {
        return ByteRange(0L, (size - 1L).coerceAtLeast(-1L), partial = false)
    }
    if (!header.startsWith("bytes=", ignoreCase = true) || size == 0L) return null
    val spec = header.substringAfter('=').substringBefore(',').trim()
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val startText = spec.substring(0, dash).trim()
    val endText = spec.substring(dash + 1).trim()

    return if (startText.isEmpty()) {
        val suffix = endText.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val start = (size - suffix).coerceAtLeast(0L)
        ByteRange(start, size - 1L, partial = true)
    } else {
        val start = startText.toLongOrNull()?.takeIf { it >= 0L && it < size } ?: return null
        val end = if (endText.isEmpty()) {
            size - 1L
        } else {
            endText.toLongOrNull()?.coerceAtMost(size - 1L) ?: return null
        }
        if (end < start) return null
        ByteRange(start, end, partial = true)
    }
}

internal fun castMimeType(entry: XEntry): String {
    entry.mime?.takeIf { it.isNotBlank() }?.let { return it }
    return when (entry.extension.lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "oga", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }
}

private fun relaySourceUri(entry: XEntry): Uri? = when {
    entry.localPath != null -> File(entry.localPath).toUri()
    entry.scheme == "content" -> entry.id.toUri()
    entry.scheme == XId.SCHEME_SMB -> entry.id.toUri()
    entry.scheme == XId.SCHEME_ROOT ->
        Uri.Builder().scheme(XId.SCHEME_ROOT).path(entry.path).build()
    else -> null
}

private fun relaySize(context: Context, entry: XEntry, uri: Uri): Long {
    if (entry.size >= 0L) return entry.size
    entry.localPath?.let { path ->
        val file = File(path)
        if (file.isFile) return file.length()
    }
    if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
            }
        }
    }
    return -1L
}

private fun findLanIpv4(context: Context): Inet4Address? {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    connectivity.activeNetwork?.let { network ->
        connectivity.getLinkProperties(network)
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.let { return it }
    }

    return runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
    }.getOrNull()
}

@UnstableApi
internal class XFilesCastMediaItemConverter(
    private val relay: CastMediaRelay,
    private val originals: Map<String, MediaItem>,
) : MediaItemConverter {
    private val delegate = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val remoteUri = relay.urlFor(mediaItem.mediaId)
            ?: return delegate.toMediaQueueItem(mediaItem)
        return delegate.toMediaQueueItem(
            mediaItem.buildUpon()
                .setUri(remoteUri)
                .build(),
        )
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val converted = delegate.toMediaItem(mediaQueueItem)
        return originals[converted.mediaId] ?: converted
    }
}
