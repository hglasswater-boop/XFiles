package app.local1st.files.core.util

import android.content.Intent
import android.net.Uri
import android.test.mock.MockContext
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedIntentResolverTest {
    @Test
    fun rejectsNonShareIntent() {
        val result = runCatching {
            SharedIntentResolver.resolve(MockContext(), Intent(Intent.ACTION_VIEW, Uri.parse("file:///tmp/a.txt")))
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsShareWithoutFileStream() {
        val result = runCatching {
            SharedIntentResolver.resolve(MockContext(), Intent(Intent.ACTION_SEND).setType("text/plain"))
        }
        assertTrue(result.isFailure)
    }
}
