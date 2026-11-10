package com.camlab.camlink.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaXmlTest {

    private val description = """
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>[Camera]WB1100F</friendlyName>
            <manufacturer>Samsung Electronics</manufacturer>
            <modelName>WB1100F</modelName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                <controlURL>/upnp/control/ContentDirectory1</controlURL>
                <eventSubURL>/upnp/event/ContentDirectory1</eventSubURL>
                <SCPDURL>/ContentDirectory.xml</SCPDURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun `parses device description and resolves control url`() {
        val device = DlnaXml.parseDescription(description, "http://192.168.1.1:7676/smp_6_")

        assertEquals("[Camera]WB1100F", device?.friendlyName)
        assertEquals("WB1100F", device?.modelName)
        assertEquals("Samsung Electronics", device?.manufacturer)
        assertEquals("192.168.1.1", device?.ip)
        assertEquals(
            "http://192.168.1.1:7676/upnp/control/ContentDirectory1",
            device?.contentDirectoryUrl
        )
    }

    @Test
    fun `rejects a device without a device element`() {
        assertNull(DlnaXml.parseDescription("<root/>", "http://192.168.1.1/"))
    }

    @Test
    fun `resolves relative and absolute url references`() {
        assertEquals(
            "http://192.168.1.1:7676/media/SAM_0001.JPG",
            DlnaXml.resolveUrl("http://192.168.1.1:7676/control", "/media/SAM_0001.JPG")
        )
        assertEquals(
            "http://192.168.1.1:7676/control/other/a.jpg",
            DlnaXml.resolveUrl("http://192.168.1.1:7676/control/x", "other/a.jpg")
        )
        assertEquals(
            "https://cdn.example.com/a.jpg",
            DlnaXml.resolveUrl("http://192.168.1.1/control", "https://cdn.example.com/a.jpg")
        )
    }

    @Test
    fun `parses browse response and prefers the full size resource`() {
        val soap = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                  <Result>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"&gt;
                    &lt;item id="1" parentID="0" restricted="1"&gt;
                      &lt;dc:title&gt;SAM_0001.JPG&lt;/dc:title&gt;
                      &lt;dc:date&gt;2015-06-01T10:00:00&lt;/dc:date&gt;
                      &lt;res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_OP=01" size="1234"&gt;http://192.168.1.1:7676/media/SAM_0001.JPG&lt;/res&gt;
                      &lt;res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" size="100"&gt;/thumbs/SAM_0001.JPG&lt;/res&gt;
                    &lt;/item&gt;
                    &lt;container id="DCIM" parentID="0"&gt;&lt;dc:title&gt;DCIM&lt;/dc:title&gt;&lt;/container&gt;
                  &lt;/DIDL-Lite&gt;</Result>
                  <TotalMatches>1</TotalMatches>
                  <NumberReturned>1</NumberReturned>
                </u:BrowseResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val page = DlnaXml.parseBrowseResponse(soap, "http://192.168.1.1:7676/control")

        assertEquals(1, page.totalMatches)
        assertEquals(1, page.numberReturned)
        assertEquals(1, page.content.items.size)
        assertEquals(1, page.content.containers.size)
        assertEquals("DCIM", page.content.containers.first().title)

        val item = page.content.items.first()
        assertEquals("SAM_0001.JPG", item.title)
        assertEquals("http://192.168.1.1:7676/media/SAM_0001.JPG", item.url)
        assertEquals("http://192.168.1.1:7676/thumbs/SAM_0001.JPG", item.thumbUrl)
        assertEquals(1234L, item.size)
        assertEquals("image/jpeg", item.mime)
        assertEquals(false, item.isVideo)
    }

    @Test
    fun `treats a video mime type as video`() {
        val didl = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <item id="7" parentID="0" restricted="1">
                <dc:title>SAM_0007.MP4</dc:title>
                <res protocolInfo="http-get:*:video/mp4:*" size="999">http://192.168.1.1:7676/media/SAM_0007.MP4</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()

        val content = DlnaXml.parseDidl(didl, "http://192.168.1.1:7676/control")

        assertEquals(1, content.items.size)
        assertTrue(content.items.first().isVideo)
    }

    @Test
    fun `ignores items that have no resource`() {
        val didl = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <item id="9" parentID="0" restricted="1"><dc:title>empty</dc:title></item>
            </DIDL-Lite>
        """.trimIndent()

        val content = DlnaXml.parseDidl(didl, "http://192.168.1.1/control")

        assertTrue(content.items.isEmpty())
    }

    @Test
    fun `empty browse result yields an empty page`() {
        val page = DlnaXml.parseBrowseResponse("<broken", "http://192.168.1.1/control")

        assertEquals(0, page.totalMatches)
        assertTrue(page.content.items.isEmpty())
    }
}
