package app.local1st.files.ui.viewer

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlaybackFailureTest {
    private fun error(code: Int) = PlaybackException("test", null, code)

    @Test
    fun fileReadFailuresAreNotMisreportedAsUnsupportedCodecs() {
        assertEquals(
            VideoPlaybackFailure.FILE_IO,
            classifyVideoPlaybackFailure(error(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)),
        )
        assertEquals(
            VideoPlaybackFailure.FILE_IO,
            classifyVideoPlaybackFailure(error(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)),
        )
        assertEquals(
            VideoPlaybackFailure.FILE_IO,
            classifyVideoPlaybackFailure(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)),
        )
    }

    @Test
    fun decoderFailuresKeepTheCodecFallback() {
        assertEquals(
            VideoPlaybackFailure.DECODER,
            classifyVideoPlaybackFailure(error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)),
        )
        assertEquals(
            VideoPlaybackFailure.DECODER,
            classifyVideoPlaybackFailure(error(PlaybackException.ERROR_CODE_DECODING_FAILED)),
        )
    }

    @Test
    fun unexpectedPlaybackErrorsRemainDistinctFromFileAccessErrors() {
        assertEquals(
            VideoPlaybackFailure.OTHER,
            classifyVideoPlaybackFailure(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)),
        )
        assertEquals(
            VideoPlaybackFailure.OTHER,
            classifyVideoPlaybackFailure(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)),
        )
    }
}
