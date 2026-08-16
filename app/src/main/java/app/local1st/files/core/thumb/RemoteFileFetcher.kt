package app.local1st.files.core.thumb

import app.local1st.files.core.fs.XEntry
import app.local1st.files.di.Graph
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.IOException
import okio.FileSystem
import okio.buffer
import okio.source

/** Remote filesystem image model keyed by semantic id + metadata for cache invalidation. */
data class RemoteFile(val entry: XEntry)

/** Streams an image directly through the registered XFileSystem (SMB included). */
class RemoteFileFetcher(private val data: RemoteFile) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val entry = data.entry
        val input = try {
            Graph.fsRegistry.forEntry(entry).openIn(entry)
        } catch (error: Throwable) {
            if (error is Error) throw error
            throw IOException("Cannot read ${entry.name}", error)
        }
        return try {
            SourceFetchResult(
                source = ImageSource(input.source().buffer(), FileSystem.SYSTEM),
                mimeType = entry.mime,
                dataSource = DataSource.NETWORK,
            )
        } catch (error: Throwable) {
            runCatching { input.close() }
            if (error is Error) throw error
            throw IOException("Cannot decode ${entry.name}", error)
        }
    }

    class Factory : Fetcher.Factory<RemoteFile> {
        override fun create(data: RemoteFile, options: Options, imageLoader: ImageLoader): Fetcher =
            RemoteFileFetcher(data)
    }

    class Key : Keyer<RemoteFile> {
        override fun key(data: RemoteFile, options: Options): String = with(data.entry) {
            "remote-file:$id:$mtime:$size"
        }
    }
}
