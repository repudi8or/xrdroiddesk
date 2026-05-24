package com.repudi8or.xrdroiddesk.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UvcStreamControlTest {
    @Test
    fun `DEFAULT serializes to 34 bytes`() {
        assertEquals(34, UvcStreamControl.DEFAULT.toBytes().size)
    }

    @Test
    fun `fromBytes round-trips formatIndex and frameIndex`() {
        val original = UvcStreamControl.DEFAULT.copy(formatIndex = 2, frameIndex = 3)
        val roundTripped = UvcStreamControl.fromBytes(original.toBytes())
        assertEquals(2, roundTripped.formatIndex)
        assertEquals(3, roundTripped.frameIndex)
    }

    @Test
    fun `fromBytes round-trips frameInterval`() {
        val original = UvcStreamControl.DEFAULT.copy(frameIntervalNs100 = 333333L)
        val roundTripped = UvcStreamControl.fromBytes(original.toBytes())
        assertEquals(333333L, roundTripped.frameIntervalNs100)
    }

    @Test
    fun `frameInterval is written at byte offset 4 as little-endian uint32`() {
        val bytes = UvcStreamControl.DEFAULT.copy(frameIntervalNs100 = 0x00123456L).toBytes()
        assertEquals(0x56.toByte(), bytes[4])
        assertEquals(0x34.toByte(), bytes[5])
        assertEquals(0x12.toByte(), bytes[6])
        assertEquals(0x00.toByte(), bytes[7])
    }

    @Test
    fun `bmHint is written as 0x0001 little-endian at offset 0`() {
        val bytes = UvcStreamControl.DEFAULT.toBytes()
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
    }
}
