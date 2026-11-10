package com.camlab.camlink.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungLinkTest {

    @Test
    fun `dlna headers identify CamLink as the Samsung app`() {
        val headers = SamsungLink.dlnaHeaders("AA:BB:CC:DD:EE:FF")

        assertEquals("SEC_RVF_ML_AA:BB:CC:DD:EE:FF", headers["User-Agent"])
        assertEquals("manual", headers["Access-Method"])
    }

    @Test
    fun `handshake headers add the phone address but stay read-only`() {
        val headers = SamsungLink.handshakeHeaders("AA:BB:CC:DD:EE:FF", "192.168.101.11")

        assertEquals("AA:BB:CC:DD:EE:FF", headers["HOST-Mac"])
        assertEquals("192.168.101.11", headers["HOST-Address"])
        assertTrue(headers.keys.none { it.equals("Content-Length", ignoreCase = true) })
    }

    @Test
    fun `handshake starts at smp_2_ and covers the camera ports`() {
        assertEquals("/smp_2_", SamsungLink.HANDSHAKE_PATHS.first())
        assertTrue(SamsungLink.PORTS.contains(7676))
        assertTrue(SamsungLink.PORTS.contains(49152))
    }
}
