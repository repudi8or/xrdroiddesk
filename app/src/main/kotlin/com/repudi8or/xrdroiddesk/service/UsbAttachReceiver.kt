package com.repudi8or.xrdroiddesk.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.repudi8or.xrdroiddesk.camera.XRealGlassesCamera

// Manifest-registered receiver for USB_DEVICE_ATTACHED. Android delivers this ~30–50ms
// after physical plug-in vs ~130ms for dynamically-registered receivers, which is enough
// to get HOST_TYPE inside the MCU's ~100ms config window.
class UsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
        if (device == null ||
            device.vendorId != XRealGlassesCamera.VENDOR_ID ||
            device.productId != XRealGlassesCamera.PRODUCT_ID
        ) {
            return
        }
        Log.i(TAG, "USB_DEVICE_ATTACHED (manifest receiver) — VID=${device.vendorId.toString(16)} PID=${device.productId.toString(16)}")
        GestureAccessibilityService.instance?.onUsbDeviceAttached(device)
    }

    companion object {
        private const val TAG = "UsbAttachReceiver"
    }
}
