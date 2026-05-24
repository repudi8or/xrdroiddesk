package com.repudi8or.xrdroiddesk.camera

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class XRealGlassesCameraTest {
    private lateinit var usbManager: UsbManager
    private lateinit var camera: XRealGlassesCamera

    @BeforeEach
    fun setUp() {
        usbManager = mockk()
        camera = XRealGlassesCamera(usbManager = usbManager, onFrame = {})
    }

    @Test
    fun `findDevice returns null when device list is empty`() {
        every { usbManager.deviceList } returns hashMapOf()
        assertNull(camera.findDevice())
    }

    @Test
    fun `findDevice returns null when no device matches VID and PID`() {
        val wrong = mockUsbDevice(vendorId = 0x1234, productId = 0x5678)
        every { usbManager.deviceList } returns hashMapOf("wrong" to wrong)
        assertNull(camera.findDevice())
    }

    @Test
    fun `findDevice returns device matching XReal VID and PID`() {
        val glasses = mockUsbDevice(vendorId = XRealGlassesCamera.VENDOR_ID, productId = XRealGlassesCamera.PRODUCT_ID)
        every { usbManager.deviceList } returns hashMapOf("xreal" to glasses)
        assertEquals(glasses, camera.findDevice())
    }

    @Test
    fun `findDevice ignores devices with correct VID but wrong PID`() {
        val notGlasses = mockUsbDevice(vendorId = XRealGlassesCamera.VENDOR_ID, productId = 0x9999)
        every { usbManager.deviceList } returns hashMapOf("other" to notGlasses)
        assertNull(camera.findDevice())
    }

    @Test
    fun `findDevice ignores devices with correct PID but wrong VID`() {
        val notGlasses = mockUsbDevice(vendorId = 0x9999, productId = XRealGlassesCamera.PRODUCT_ID)
        every { usbManager.deviceList } returns hashMapOf("other" to notGlasses)
        assertNull(camera.findDevice())
    }

    private fun mockUsbDevice(
        vendorId: Int,
        productId: Int,
    ): UsbDevice =
        mockk<UsbDevice>().also {
            every { it.vendorId } returns vendorId
            every { it.productId } returns productId
        }
}
