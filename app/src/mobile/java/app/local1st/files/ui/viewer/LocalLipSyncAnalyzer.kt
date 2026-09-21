package app.local1st.files.ui.viewer

import android.graphics.AudioFormat
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class LocalLipSyncAnalysis(
    val estimate: LipSyncEstimate,
    val faceCoverage: Float,
    val analyzedStartMs: Long,
    val analyzedEndMs: Long,
) {
    val confidence: Float = (estimate.confidence * faceCoverage).coerceIn(0f, 1f)
}

internal sealed interface LocalLipSyncAnalysisResult {
    data class Success(val analysis: LocalLipSyncAnalysis) : LocalLipSyncAnalysisResult
    data class Unavailable(val reason: String) : LocalLipSyncAnalysisResult
}

/**
 * Offline lip-sync estimator for directly accessible local videos.
 *
 * The bundled ML Kit face detector supplies inner-lip contours. Audio is decoded locally through
 * MediaCodec. No frame/audio data leaves the device. Remote/root sources keep the manual A/V
 * control but are deliberately excluded until they have a bounded random-access analysis path.
 */
internal object LocalLipSyncAnalyzer {
    suspend fun analyze(
        localPath: String?,
        centerPositionMs: Long,
    ): LocalLipSyncAnalysisResult = withContext(Dispatchers.Default) {
        val path = localPath?.takeIf { File(it).isFile }
            ?: return@withContext LocalLipSyncAnalysisResult.Unavailable(
                "自動解析は現在、端末内のローカル動画に対応しています。",
            )

        val durationMs = mediaDurationMs(path)
        if (durationMs <= MIN_ANALYZABLE_DURATION_MS) {
            return@withContext LocalLipSyncAnalysisResult.Unavailable(
                "解析できる長さの動画ではありません。",
            )
        }
        val window = chooseAnalysisWindow(centerPositionMs, durationMs)

        val mouth = runCatching { extractMouthLevels(path, window.startMs, window.endMs) }
            .getOrElse {
                return@withContext LocalLipSyncAnalysisResult.Unavailable(
                    "この区間では口の動きを安定して検出できませんでした。",
                )
            }
        if (mouth.faceCoverage < MIN_FACE_COVERAGE) {
            return@withContext LocalLipSyncAnalysisResult.Unavailable(
                "顔が映っている時間が少ないため、自動判定できませんでした。",
            )
        }

        val audioLevels = runCatching {
            decodeAudioLevels(
                path = path,
                startMs = window.startMs,
                endMs = window.endMs,
                binMs = SIGNAL_STEP_MS,
            )
        }.getOrElse {
            return@withContext LocalLipSyncAnalysisResult.Unavailable(
                "この動画の音声を自動解析できませんでした。手動補正は利用できます。",
            )
        }

        val mouthMotion = motionFromLevels(mouth.levels)
        val speechOnset = motionFromLevels(DoubleArray(audioLevels.size) { index ->
            ln1p(audioLevels[index] * AUDIO_LOG_SCALE)
        })
        val estimate = estimateLipSyncOffset(
            mouthMotion = mouthMotion,
            speechOnset = speechOnset,
            sampleStepMs = SIGNAL_STEP_MS,
            maxOffsetMs = MAX_ESTIMATED_OFFSET_MS,
        ) ?: return@withContext LocalLipSyncAnalysisResult.Unavailable(
            "口の動きと音声の対応がはっきりしない区間でした。話している顔が見える位置で再度お試しください。",
        )

        LocalLipSyncAnalysisResult.Success(
            LocalLipSyncAnalysis(
                estimate = estimate,
                faceCoverage = mouth.faceCoverage,
                analyzedStartMs = window.startMs,
                analyzedEndMs = window.endMs,
            ),
        )
    }
}

private data class AnalysisWindow(val startMs: Long, val endMs: Long)

private data class MouthLevels(
    val levels: DoubleArray,
    val faceCoverage: Float,
)

private fun chooseAnalysisWindow(centerMs: Long, durationMs: Long): AnalysisWindow {
    val length = minOf(ANALYSIS_WINDOW_MS, durationMs)
    val desiredStart = centerMs - length / 2L
    val start = desiredStart.coerceIn(0L, (durationMs - length).coerceAtLeast(0L))
    return AnalysisWindow(start, start + length)
}

private fun mediaDurationMs(path: String): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
    } finally {
        retriever.release()
    }
}

private suspend fun extractMouthLevels(
    path: String,
    startMs: Long,
    endMs: Long,
): MouthLevels {
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.12f)
            .build(),
    )
    val retriever = MediaMetadataRetriever()
    retriever.setDataSource(path)

    val gridCount = (((endMs - startMs) / SIGNAL_STEP_MS) + 1L).toInt()
    val sparse = DoubleArray(gridCount) { Double.NaN }
    var requestedFrames = 0
    var detectedFrames = 0
    try {
        var timeMs = startMs
        while (timeMs <= endMs) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            requestedFrames++
            val bitmap = retriever.getFrameAtTime(
                timeMs * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
            )
            if (bitmap != null) {
                try {
                    val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
                    val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    val aperture = face?.let(::normalizedMouthAperture)
                    if (aperture != null && aperture.isFinite()) {
                        val index = ((timeMs - startMs) / SIGNAL_STEP_MS).toInt()
                        if (index in sparse.indices) sparse[index] = aperture
                        detectedFrames++
                    }
                } finally {
                    recycleFrame(bitmap)
                }
            }
            timeMs += VIDEO_SAMPLE_INTERVAL_MS
        }
    } finally {
        retriever.release()
        detector.close()
    }

    val coverage = if (requestedFrames == 0) 0f else detectedFrames.toFloat() / requestedFrames
    return MouthLevels(
        levels = interpolateSparseSignal(sparse),
        faceCoverage = coverage,
    )
}

private fun normalizedMouthAperture(face: Face): Double? {
    val upper = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points.orEmpty()
    val lower = face.getContour(FaceContour.LOWER_LIP_TOP)?.points.orEmpty()
    if (upper.isEmpty() || lower.isEmpty()) return null
    val faceHeight = face.boundingBox.height().toDouble()
    if (faceHeight <= 0.0) return null

    val upperY = upper.sumOf { it.y.toDouble() } / upper.size
    val lowerY = lower.sumOf { it.y.toDouble() } / lower.size
    return abs(lowerY - upperY) / faceHeight
}

private fun interpolateSparseSignal(sparse: DoubleArray): DoubleArray {
    if (sparse.none { it.isFinite() }) return DoubleArray(sparse.size)
    val output = sparse.copyOf()
    var previousKnown = -1
    for (index in output.indices) {
        if (!output[index].isFinite()) continue
        if (previousKnown < 0) {
            for (fill in 0 until index) output[fill] = output[index]
        } else if (index - previousKnown > 1) {
            val from = output[previousKnown]
            val to = output[index]
            val span = index - previousKnown
            for (fill in previousKnown + 1 until index) {
                val fraction = (fill - previousKnown).toDouble() / span
                output[fill] = from + (to - from) * fraction
            }
        }
        previousKnown = index
    }
    if (previousKnown >= 0) {
        for (fill in previousKnown + 1 until output.size) output[fill] = output[previousKnown]
    }
    return output
}

private fun decodeAudioLevels(
    path: String,
    startMs: Long,
    endMs: Long,
    binMs: Int,
): DoubleArray {
    val extractor = MediaExtractor()
    extractor.setDataSource(path)
    val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { index ->
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: run {
        extractor.release()
        throw IllegalArgumentException("No audio track")
    }

    val inputFormat = extractor.getTrackFormat(audioTrackIndex)
    val mime = inputFormat.getString(MediaFormat.KEY_MIME)
        ?: run {
            extractor.release()
            throw IllegalArgumentException("Audio MIME unavailable")
        }
    extractor.selectTrack(audioTrackIndex)
    extractor.seekTo(startMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

    val codec = MediaCodec.createDecoderByType(mime)
    codec.configure(inputFormat, null, null, 0)
    codec.start()

    val binCount = (((endMs - startMs) / binMs) + 1L).toInt()
    val sums = DoubleArray(binCount)
    val counts = LongArray(binCount)
    val info = MediaCodec.BufferInfo()
    var inputEnded = false
    var outputEnded = false
    var outputFormat = inputFormat

    try {
        while (!outputEnded) {
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex)
                    val sampleTimeUs = extractor.sampleTime
                    if (input == null || sampleTimeUs < 0L || sampleTimeUs > endMs * 1_000L) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            maxOf(sampleTimeUs, 0L),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEnded = true
                    } else {
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                maxOf(sampleTimeUs, 0L),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        accumulatePcmEnergy(
                            buffer = buffer,
                            info = info,
                            format = outputFormat,
                            analysisStartUs = startMs * 1_000L,
                            analysisEndUs = endMs * 1_000L,
                            binUs = binMs * 1_000L,
                            sums = sums,
                            counts = counts,
                        )
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    } finally {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    return DoubleArray(binCount) { index ->
        if (counts[index] == 0L) 0.0 else sqrt(sums[index] / counts[index])
    }
}

private fun accumulatePcmEnergy(
    buffer: ByteBuffer,
    info: MediaCodec.BufferInfo,
    format: MediaFormat,
    analysisStartUs: Long,
    analysisEndUs: Long,
    binUs: Long,
    sums: DoubleArray,
    counts: LongArray,
) {
    val sampleRate = format.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0)
    val channels = format.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 0)
    if (sampleRate <= 0 || channels <= 0) return
    val encoding = if (android.os.Build.VERSION.SDK_INT >= 24) {
        format.integerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    } else {
        AudioFormat.ENCODING_PCM_16BIT
    }
    val bytesPerSample = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_8BIT -> 1
        else -> 2
    }
    val frameSize = bytesPerSample * channels
    if (frameSize <= 0 || info.size < frameSize) return

    val view = buffer.duplicate().order(ByteOrder.nativeOrder())
    view.position(info.offset)
    view.limit((info.offset + info.size).coerceAtMost(view.capacity()))
    val frames = view.remaining() / frameSize
    for (frame in 0 until frames) {
        val timeUs = info.presentationTimeUs + frame.toLong() * 1_000_000L / sampleRate
        var energy = 0.0
        for (channel in 0 until channels) {
            val amplitude = when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> view.float.toDouble().coerceIn(-1.0, 1.0)
                AudioFormat.ENCODING_PCM_8BIT -> ((view.get().toInt() and 0xff) - 128) / 128.0
                else -> view.short / 32768.0
            }
            energy += amplitude * amplitude
        }
        if (timeUs in analysisStartUs..analysisEndUs) {
            val bin = ((timeUs - analysisStartUs) / binUs).toInt()
            if (bin in sums.indices) {
                sums[bin] += energy / channels
                counts[bin]++
            }
        }
    }
}

private fun MediaFormat.integerOrDefault(key: String, fallback: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

private suspend fun <T> Task<T>.awaitTask(): T = suspendCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
}

private fun recycleFrame(bitmap: Bitmap) {
    if (!bitmap.isRecycled) bitmap.recycle()
}

private const val ANALYSIS_WINDOW_MS = 10_000L
private const val MIN_ANALYZABLE_DURATION_MS = 4_000L
private const val VIDEO_SAMPLE_INTERVAL_MS = 100L
private const val SIGNAL_STEP_MS = 50
private const val MAX_ESTIMATED_OFFSET_MS = 1_500
private const val MIN_FACE_COVERAGE = 0.45f
private const val AUDIO_LOG_SCALE = 24.0
private const val CODEC_TIMEOUT_US = 10_000L
