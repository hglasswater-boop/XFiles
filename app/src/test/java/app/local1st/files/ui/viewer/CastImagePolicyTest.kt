package app.local1st.files.ui.viewer

import app.local1st.files.core.fs.XEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastImagePolicyTest {
    private fun image(name: String, mime: String? = null): XEntry =
        XEntry(id = "smb://connection/photos/$name", name = name, isDir = false, size = 1024, mime = mime)

    @Test
    fun supportedImageExtensionsHaveConcreteCastMime() {
        mapOf(
            "a.jpg" to "image/jpeg",
            "a.JPEG" to "image/jpeg",
            "a.png" to "image/png",
            "a.gif" to "image/gif",
            "a.webp" to "image/webp",
            "a.bmp" to "image/bmp",
            "a.apng" to "image/apng",
        ).forEach { (filename, mime) ->
            assertEquals(filename, mime, castImageMimeType(image(filename)))
        }
    }

    @Test
    fun filenameWinsOverGenericOrIncorrectMetadata() {
        assertEquals("image/jpeg", castImageMimeType(image("photo.jpg", "application/octet-stream")))
        assertEquals("image/png", castImageMimeType(image("scan.png", "image/*")))
    }

    @Test
    fun supportedExplicitMimeCoversUnknownExtension() {
        assertEquals("image/png", castImageMimeType(image("untitled", "image/png")))
        assertEquals("image/jpeg", castImageMimeType(image("untitled", " IMAGE/JPEG; charset=binary")))
    }

    @Test
    fun unsupportedAndAmbiguousImageFormatsAreRejected() {
        for (filename in listOf("photo.heic", "photo.avif", "photo.tiff", "photo.svg", "untitled")) {
            assertNull(filename, castImageMimeType(image(filename)))
        }
        assertNull(castImageMimeType(image("untitled", "image/*")))
        assertNull(castImageMimeType(image("photo.heic", "application/octet-stream")))
    }

    @Test
    fun sameImageInSameSessionDoesNotReload() {
        assertTrue(shouldSendCastImage(null, "img1", sessionChanged = false))
        assertFalse(shouldSendCastImage("img1", "img1", sessionChanged = false))
        assertTrue(shouldSendCastImage("img1", "img2", sessionChanged = false))
        assertTrue(shouldSendCastImage("img1", "img1", sessionChanged = true))
    }

    @Test
    fun unsupportedTransportIsNotOfferedAsCastable() {
        assertTrue(canCastRelayImage(image("a.jpg")))
        assertFalse(canCastRelayImage(image("a.jpg").copy(id = "zip:///photos.zip!/a.jpg")))
        assertFalse(canCastRelayImage(image("a.jpg").copy(id = "apps://package")))
        assertFalse(canCastRelayImage(image("a.jpg").copy(isDir = true)))
    }
}
