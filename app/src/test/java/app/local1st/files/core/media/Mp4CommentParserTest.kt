package app.local1st.files.core.media

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mp4CommentParserTest {
    private fun atom(type: String, content: ByteArray): ByteArray {
        val size = (content.size + 8).toLong()
        val header = byteArrayOf(
            (size shr 24).toByte(), (size shr 16).toByte(),
            (size shr 8).toByte(), size.toByte(),
        ) + type.toByteArray(StandardCharsets.ISO_8859_1)
        return header + content
    }

    private fun tag(type: String, value: String) =
        atom(type, atom("data", ByteArray(8) + value.toByteArray(Charsets.UTF_8)))

    private fun file(comment: String? = null, description: String? = null, mdatFirst: Boolean = false): ByteArray {
        val tags = listOfNotNull(
            comment?.let { tag("©cmt", it) },
            description?.let { tag("©des", it) },
        ).fold(byteArrayOf()) { a, b -> a + b }
        val moov = atom("moov", atom("udta", atom("meta", ByteArray(4) + atom("ilst", tags))))
        val ftyp = atom("ftyp", "isom".toByteArray())
        val mdat = atom("mdat", ByteArray(100))
        return if (mdatFirst) ftyp + mdat + moov else ftyp + moov + mdat
    }

    private fun extract(bytes: ByteArray): String? = Mp4CommentParser.parse(bytes.size.toLong()) { offset, size ->
        if (offset < 0 || offset + size > bytes.size) null
        else bytes.copyOfRange(offset.toInt(), offset.toInt() + size)
    }

    @Test fun commentSupportsJapaneseAndLineBreaks() {
        assertEquals("投稿者です\nよろしく", extract(file(comment = "投稿者です\nよろしく", description = "説明")))
    }

    @Test fun onlyTheGenericCommentTagCountsAsAComment() {
        assertEquals("ユーザー設定メモ", extract(file(comment = "ユーザー設定メモ")))
        assertNull(extract(file(description = "映像の説明文")))
        assertNull(extract(file()))
    }

    @Test fun parsesMoovAfterMdatWithoutReadingVideoData() {
        assertEquals("末尾", extract(file(comment = "末尾", mdatFirst = true)))
    }

    @Test fun ignoresInvalidBoxSizes() {
        assertNull(extract(byteArrayOf(0, 0, 0, 1, 109, 111, 111, 118)))
    }
}
