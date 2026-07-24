package com.repudi8or.xrdroiddesk.camera

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class H264FrameDecoderTest {
    @Test
    fun `isAnnexB returns true for valid 4-byte start code`() {
        assertTrue(H264FrameDecoder.isAnnexB(byteArrayOf(0, 0, 0, 1, 0x67.toByte())))
    }

    @Test
    fun `isAnnexB returns false for MJPEG frame starting with FF D8`() {
        assertFalse(H264FrameDecoder.isAnnexB(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0)))
    }

    @Test
    fun `isAnnexB returns false when array is exactly 4 bytes — too short`() {
        assertFalse(H264FrameDecoder.isAnnexB(byteArrayOf(0, 0, 0, 1)))
    }

    @Test
    fun `isAnnexB returns false for empty array`() {
        assertFalse(H264FrameDecoder.isAnnexB(byteArrayOf()))
    }

    @Test
    fun `isAnnexB returns false for 3-byte start code 00 00 01`() {
        assertFalse(H264FrameDecoder.isAnnexB(byteArrayOf(0, 0, 1, 0x67.toByte(), 0)))
    }

    @Test
    fun `isAnnexB returns false when fourth byte is not 01`() {
        assertFalse(H264FrameDecoder.isAnnexB(byteArrayOf(0, 0, 0, 0, 1)))
    }
}
