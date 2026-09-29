package app.local1st.files.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryboardDuplicateFrameDetectorTest {
    @Test
    fun repeatedSyncFrameFarApartNeedsVerification() {
        assertTrue(
            shouldVerifyDuplicateStoryboardSync(
                previousFingerprint = 42L,
                currentFingerprint = 42L,
                previousTimeMs = 30_000L,
                currentTimeMs = 55_000L,
            ),
        )
    }

    @Test
    fun differentSyncFramesDoNotNeedVerification() {
        assertFalse(
            shouldVerifyDuplicateStoryboardSync(
                previousFingerprint = 41L,
                currentFingerprint = 42L,
                previousTimeMs = 30_000L,
                currentTimeMs = 55_000L,
            ),
        )
    }

    @Test
    fun nearbyDuplicateSyncFrameDoesNotTriggerExtraDecode() {
        assertFalse(
            shouldVerifyDuplicateStoryboardSync(
                previousFingerprint = 42L,
                currentFingerprint = 42L,
                previousTimeMs = 30_000L,
                currentTimeMs = 32_000L,
            ),
        )
    }

    @Test
    fun duplicateSyncWithDifferentExactFrameSwitchesToClosest() {
        assertTrue(shouldPreferClosestStoryboardFrames(42L, 99L))
    }

    @Test
    fun genuinelyStaticExactFrameKeepsSyncFastPath() {
        assertFalse(shouldPreferClosestStoryboardFrames(42L, 42L))
    }

    @Test
    fun failedExactFrameDoesNotSwitchModes() {
        assertFalse(shouldPreferClosestStoryboardFrames(42L, null))
    }
}
