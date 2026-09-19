package app.local1st.files.core.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.local1st.files.core.fs.SmbRandomAccessFile
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.prefs.SmbConnectionRepo
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/**
 * Rebuilds a video's MP4 container without decoding or re-encoding any media samples.
 *
 * Android's MediaExtractor parses the source container and MediaMuxer writes the selected tracks
 * into a fresh MP4 index. The sample payload is copied verbatim, which makes this useful for
 * isolating container/index problems from codec/decoder problems without bundling FFmpeg.
 */
object VideoRemuxer {
    data class Result(
        val trackCount: Int,
        val copiedBytes: Long,
    )

    fun remuxToMp4(
        context: Context,
        source: XEntry,
        outputUri: Uri,
        smbConnections: SmbConnectionRepo,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result {
        val resolver = context.contentResolver
        var inputFd: ParcelFileDescriptor? = null
        var remoteSource: MediaDataSource? = null
        var outputFd: ParcelFileDescriptor? = null
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var completed = false

        try {
            when {
                source.localPath != null -> extractor.setDataSource(source.localPath)
                source.scheme == "content" -> {
                    inputFd = resolver.openFileDescriptor(Uri.parse(source.id), "r")
                        ?: throw IOException("入力動画を開けません")
                    extractor.setDataSource(inputFd.fileDescriptor)
                }
                source.scheme == XId.SCHEME_ROOT -> {
                    val transport = PrivilegedAccess.fdTransport()
                        ?: throw IOException("Root動画を開くための権限がありません")
                    inputFd = transport.openFd(source.path, write = false)
                        ?: throw IOException("入力動画を開けません")
                    extractor.setDataSource(inputFd.fileDescriptor)
                }
                source.scheme == XId.SCHEME_SMB -> {
                    remoteSource = SmbMediaDataSource(source, smbConnections)
                    extractor.setDataSource(remoteSource)
                }
                else -> throw IOException("この場所の動画はMP4再構築に対応していません")
            }

            if (extractor.trackCount <= 0) throw IOException("動画トラックを読み取れません")

            val sourceTracks = ArrayList<Track>(extractor.trackCount)
            var rotationDegrees = 0
            var maximumInputSize = DEFAULT_BUFFER_BYTES
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: throw IOException("トラック形式を判定できません")
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) {
                    throw IOException("MP4へコピーできないトラックが含まれています: $mime")
                }
                sourceTracks += Track(index, format)
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotationDegrees = format.getInteger(MediaFormat.KEY_ROTATION)
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maximumInputSize = maxOf(
                        maximumInputSize,
                        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE),
                    )
                }
            }
            if (maximumInputSize > MAX_BUFFER_BYTES) {
                throw IOException("1サンプルが大きすぎるためMP4再構築できません")
            }

            outputFd = resolver.openFileDescriptor(outputUri, "rwt")
                ?: throw IOException("出力先を開けません")
            muxer = MediaMuxer(
                outputFd.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            if (rotationDegrees != 0) muxer.setOrientationHint(rotationDegrees)

            val trackMap = HashMap<Int, Int>(sourceTracks.size)
            sourceTracks.forEach { track ->
                val targetTrack = try {
                    muxer.addTrack(track.format)
                } catch (error: RuntimeException) {
                    val mime = track.format.getString(MediaFormat.KEY_MIME) ?: "unknown"
                    throw IOException("このコーデックはMP4へコピーできません: $mime", error)
                }
                trackMap[track.sourceIndex] = targetTrack
                extractor.selectTrack(track.sourceIndex)
            }

            muxer.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocateDirect(maximumInputSize)
            val info = MediaCodec.BufferInfo()
            val totalBytes = source.size.coerceAtLeast(0L)
            var copiedBytes = 0L

            while (true) {
                if (isCancelled()) throw CancellationException("MP4再構築をキャンセルしました")

                val sourceTrack = extractor.sampleTrackIndex
                if (sourceTrack < 0) break
                val targetTrack = trackMap[sourceTrack]
                    ?: throw IOException("トラックの対応付けに失敗しました")

                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                if (sampleSize > buffer.capacity()) {
                    throw IOException("メディアサンプルが大きすぎるためMP4再構築できません")
                }
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                    throw IOException("暗号化された動画はMP4再構築できません")
                }

                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(targetTrack, buffer, info)
                copiedBytes += sampleSize.toLong()
                onProgress(copiedBytes, totalBytes)
                extractor.advance()
            }

            muxer.stop()
            muxerStarted = false
            completed = true
            if (totalBytes > 0L) onProgress(totalBytes, totalBytes)
            return Result(sourceTracks.size, copiedBytes)
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            runCatching { remoteSource?.close() }
            runCatching { inputFd?.close() }
            runCatching { outputFd?.close() }
            if (!completed) runCatching { resolver.delete(outputUri, null, null) }
        }
    }

    private data class Track(
        val sourceIndex: Int,
        val format: MediaFormat,
    )

    private class SmbMediaDataSource(
        private val entry: XEntry,
        connections: SmbConnectionRepo,
    ) : MediaDataSource(), Closeable {
        private val file = SmbRandomAccessFile.open(entry.id, connections)

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0L || size <= 0) return -1
            return file.read(position, buffer, offset, size)
        }

        override fun getSize(): Long = entry.size.takeIf { it >= 0L }
            ?: throw IOException("SMB動画のサイズを取得できません")

        override fun close() = file.close()
    }

    private const val DEFAULT_BUFFER_BYTES = 8 * 1024 * 1024
    private const val MAX_BUFFER_BYTES = 64 * 1024 * 1024
}
