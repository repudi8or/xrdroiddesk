package com.repudi8or.xrdroiddesk.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class UvcStreamControl(
    val formatIndex: Int,
    val frameIndex: Int,
    val frameIntervalNs100: Long,
    val maxVideoFrameSize: Int = 0,
    val maxPayloadTransferSize: Int = 0,
) {
    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0001)
        buf.put(formatIndex.toByte())
        buf.put(frameIndex.toByte())
        buf.putInt(frameIntervalNs100.toInt())
        buf.putShort(0) // wKeyFrameRate
        buf.putShort(0) // wPFrameRate
        buf.putShort(0) // wCompQuality
        buf.putShort(0) // wCompWindowSize
        buf.putShort(0) // wDelay
        buf.putInt(maxVideoFrameSize)
        buf.putInt(maxPayloadTransferSize)
        buf.putInt(0) // dwClockFrequency
        buf.put(0) // bmFramingInfo
        buf.put(0) // bPreferedVersion
        buf.put(0) // bMinVersion
        buf.put(0) // bMaxVersion
        return buf.array()
    }

    companion object {
        const val SIZE = 34

        val DEFAULT =
            UvcStreamControl(
                formatIndex = 1,
                frameIndex = 1,
                frameIntervalNs100 = 666667L, // ~15 fps (1s / 15 = 66.67ms = 666700 * 100ns)
            )

        fun fromBytes(bytes: ByteArray): UvcStreamControl {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.getShort() // bmHint
            val formatIndex = buf.get().toInt() and 0xFF
            val frameIndex = buf.get().toInt() and 0xFF
            val frameInterval = buf.getInt().toLong() and 0xFFFFFFFFL
            buf.getShort()
            buf.getShort()
            buf.getShort()
            buf.getShort()
            buf.getShort()
            val maxFrameSize = buf.getInt()
            val maxPayload = buf.getInt()
            return UvcStreamControl(formatIndex, frameIndex, frameInterval, maxFrameSize, maxPayload)
        }
    }
}
