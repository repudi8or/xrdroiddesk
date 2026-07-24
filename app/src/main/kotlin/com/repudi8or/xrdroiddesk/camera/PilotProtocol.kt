package com.repudi8or.xrdroiddesk.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary protocol for the XReal pilot daemon at 169.254.1.1:50180 (TCP via USB NCM).
 *
 * Wire format (all multi-byte fields little-endian):
 *   [0x00]  1 byte   STX = 0xAA
 *   [0x01]  2 bytes  length = bytes from msgID to end of payload (exclusive of STX, length, CRC)
 *   [0x03]  2 bytes  message ID
 *   [0x05] 17 bytes  sub-header (cmdType at byte 5, rest zeros)
 *   [0x16]  N bytes  payload
 *   [0x16+N] 4 bytes CRC32 (over bytes 0x00..0x15+N, i.e. everything before the CRC)
 *
 * CRC32 uses the standard reflected polynomial 0xEDB88320 (same as zlib/java.util.zip.CRC32).
 */
object PilotProtocol {
    private const val STX: Byte = 0xAA.toByte()
    private const val HEADER_FIXED = 5 // STX + length(2) + msgID(2)
    private const val SUB_HEADER_SIZE = 17
    private const val CRC_SIZE = 4
    private const val MSG_ID_SET_USB_CONFIG = 0x00D3.toShort()
    private const val MSG_ID_GET_USB_CONFIG = 0x00D2.toShort()
    private const val CMD_TYPE_SET = 0x04.toByte()
    private const val CMD_TYPE_GET = 0x00.toByte()

    /**
     * Fields in the UsbConfigList payload. All as int32_le.
     * Order matches field order observed in the Java class (alphabetical by field name
     * in JADX output): ecm, enable, hid_ctrl, mass_storage, mtp, ncm, uac, uvc0, uvc1.
     * Adjust if live capture shows a different order.
     */
    data class UsbConfig(
        val ncm: Int = 1,
        val ecm: Int = 1,
        val uac: Int = 1,
        val hidCtrl: Int = 1,
        val mtp: Int = 0,
        val massStorage: Int = 0,
        val uvc0: Int = 0,
        val uvc1: Int = 0,
        val enable: Int = 1,
    ) {
        companion object {
            val UVC_ENABLED =
                UsbConfig(
                    ncm = 1,
                    ecm = 1,
                    uac = 1,
                    hidCtrl = 1,
                    mtp = 0,
                    massStorage = 0,
                    uvc0 = 1,
                    uvc1 = 1,
                    enable = 1,
                )
        }
    }

    /** Build a GET USB config request. No payload. */
    fun buildGet(): ByteArray = buildMessage(MSG_ID_GET_USB_CONFIG, CMD_TYPE_GET, ByteArray(0))

    /** Build a SET USB config request with the given UsbConfig. */
    fun buildSetUsbConfig(config: UsbConfig): ByteArray {
        val payload =
            ByteBuffer
                .allocate(9 * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    putInt(config.ncm)
                    putInt(config.ecm)
                    putInt(config.uac)
                    putInt(config.hidCtrl)
                    putInt(config.mtp)
                    putInt(config.massStorage)
                    putInt(config.uvc0)
                    putInt(config.uvc1)
                    putInt(config.enable)
                }.array()
        return buildMessage(MSG_ID_SET_USB_CONFIG, CMD_TYPE_SET, payload)
    }

    private fun buildMessage(
        msgId: Short,
        cmdType: Byte,
        payload: ByteArray,
    ): ByteArray {
        // length field = msgID(2) + subHeader(17) + payload
        val payloadLen = payload.size
        val length = 2 + SUB_HEADER_SIZE + payloadLen

        val totalSize = HEADER_FIXED + SUB_HEADER_SIZE + payloadLen + CRC_SIZE
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(STX)
        buf.putShort(length.toShort())
        buf.putShort(msgId)

        // Sub-header: cmdType at first byte, rest zeros
        buf.put(cmdType)
        repeat(SUB_HEADER_SIZE - 1) { buf.put(0) }

        buf.put(payload)

        // CRC32 over everything before the CRC field
        val crcData = buf.array().copyOf(totalSize - CRC_SIZE)
        buf.putInt(crc32(crcData))

        return buf.array()
    }

    /**
     * Parse a response from the pilot daemon.
     * Returns the payload bytes if the frame is valid, null otherwise.
     */
    fun parseResponse(data: ByteArray): ByteArray? {
        if (data.size < HEADER_FIXED + SUB_HEADER_SIZE + CRC_SIZE) return null
        if (data[0] != STX) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // skip STX
        val length = buf.short.toInt() and 0xFFFF
        val expectedTotal = HEADER_FIXED + length + CRC_SIZE
        if (data.size < expectedTotal) return null

        val payloadLen = length - 2 - SUB_HEADER_SIZE
        if (payloadLen < 0) return null

        // Validate CRC
        val crcOffset = expectedTotal - CRC_SIZE
        val expectedCrc = ByteBuffer.wrap(data, crcOffset, CRC_SIZE).order(ByteOrder.LITTLE_ENDIAN).int
        val computedCrc = crc32(data.copyOf(crcOffset))
        if (expectedCrc != computedCrc) return null

        val payload = ByteArray(payloadLen)
        System.arraycopy(data, HEADER_FIXED + SUB_HEADER_SIZE, payload, 0, payloadLen)
        return payload
    }

    /** Parse a UsbConfig from a 36-byte payload (9 × int32_le). */
    fun parseUsbConfig(payload: ByteArray): UsbConfig? {
        if (payload.size < 36) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return UsbConfig(
            ncm = b.int,
            ecm = b.int,
            uac = b.int,
            hidCtrl = b.int,
            mtp = b.int,
            massStorage = b.int,
            uvc0 = b.int,
            uvc1 = b.int,
            enable = b.int,
        )
    }

    /** CRC32 using reflected polynomial 0xEDB88320 (matches java.util.zip.CRC32). */
    fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc.inv()
    }
}
