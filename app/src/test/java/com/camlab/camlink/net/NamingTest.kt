package com.camlab.camlink.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {

    private fun item(
        title: String = "",
        url: String = "http://192.168.1.1/media/SAM_0001.JPG",
        mime: String = ""
    ) = MediaItem(
        id = url,
        title = title,
        url = url,
        thumbUrl = null,
        size = 0L,
        mime = mime,
        date = null
    )

    @Test
    fun `keeps an existing extension`() {
        assertEquals("SAM_0001.JPG", Naming.fileName(item(title = "SAM_0001.JPG")))
    }

    @Test
    fun `adds the extension from the mime type`() {
        assertEquals(
            "SAM_0001.jpg",
            Naming.fileName(item(title = "SAM_0001", mime = "image/jpeg"))
        )
    }

    @Test
    fun `adds the extension from the url when mime is unknown`() {
        val media = item(title = "clip", url = "http://192.168.1.1/media/clip.MP4", mime = "")
        assertEquals("clip.MP4", Naming.fileName(media))
    }

    @Test
    fun `replaces characters that are illegal in file names`() {
        val name = Naming.fileName(item(title = "a:b*c?d\"e<f>g|h.JPG"))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('*'))
        assertFalse(name.contains('?'))
        assertFalse(name.contains('"'))
        assertFalse(name.contains('<'))
        assertFalse(name.contains('>'))
        assertFalse(name.contains('|'))
        assertTrue(name.endsWith(".JPG"))
    }

    @Test
    fun `falls back to an indexed name when the title is empty`() {
        val media = item(title = "", url = "http://192.168.1.1/media/", mime = "")
        assertEquals("camlink_3", Naming.fileName(media, index = 2))
    }

    @Test
    fun `sanitize trims dots and blank values fall back`() {
        assertEquals("fallback", Naming.sanitize("   ", "fallback"))
        assertEquals("a", Naming.sanitize(".a.", "fallback"))
    }

    @Test
    fun `hasExtension only accepts plausible extensions`() {
        assertTrue(Naming.hasExtension("SAM_0001.JPG"))
        assertTrue(Naming.hasExtension("clip.mp4"))
        assertFalse(Naming.hasExtension("no_extension"))
        assertFalse(Naming.hasExtension("trailing."))
        assertFalse(Naming.hasExtension("a.superlongext"))
    }
}
