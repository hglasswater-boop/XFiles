package app.local1st.files.core.update

import app.local1st.files.core.util.SelfUpdateApkVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdateSigningIdentityTest {
    @Test
    fun exactSameSigningCertificatesAreAllowed() {
        assertTrue(SelfUpdateApkVerifier.sameSigningIdentity(setOf("A"), setOf("A")))
        assertTrue(SelfUpdateApkVerifier.sameSigningIdentity(setOf("A", "B"), setOf("B", "A")))
    }

    @Test
    fun mismatchedOrMissingSigningCertificatesAreRejected() {
        assertFalse(SelfUpdateApkVerifier.sameSigningIdentity(setOf("A"), setOf("B")))
        assertFalse(SelfUpdateApkVerifier.sameSigningIdentity(emptySet(), setOf("A")))
        assertFalse(SelfUpdateApkVerifier.sameSigningIdentity(setOf("A"), emptySet()))
    }
}
