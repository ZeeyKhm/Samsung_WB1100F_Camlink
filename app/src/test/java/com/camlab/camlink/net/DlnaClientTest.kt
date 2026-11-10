package com.camlab.camlink.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaClientTest {

    @Test
    fun `manual candidates probe the Samsung camera port first`() {
        val candidates = DlnaClient.manualCandidates("192.168.1.1")

        assertEquals("http://192.168.1.1:7676/smp_2_", candidates.first())
        assertTrue(candidates.contains("http://192.168.1.1:7676/smp_6_"))
        assertTrue(candidates.contains("http://192.168.1.1/description.xml"))
        assertTrue(candidates.contains("http://192.168.1.1:49152/description.xml"))
    }

    @Test
    fun `manual candidates keep an explicit port`() {
        val candidates = DlnaClient.manualCandidates("http://192.168.1.1:9000/")

        assertEquals("http://192.168.1.1:9000/description.xml", candidates.first())
        assertTrue(candidates.none { it.contains("7676") })
    }

    @Test
    fun `blank input has no candidates`() {
        assertTrue(DlnaClient.manualCandidates("   ").isEmpty())
    }
}
