package com.repudi8or.xrdroiddesk

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.repudi8or.xrdroiddesk.service.GestureAccessibilityService

class GrantUsbPermissionActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        val device = intentDevice(intent)
        if (device == null) {
            Log.e(TAG, "No USB device in intent")
            finish()
            return
        }
        // Notify service early — Activity fires at ~50ms via manifest filter, before the
        // dynamic receiver (~130ms). skipActivityLaunch=true prevents a duplicate launch.
        GestureAccessibilityService.instance?.onManifestUsbAttached(device)
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "Permission already granted — service handles camera via onManifestUsbAttached")
            finish()
            return
        }

        val permIntent = Intent(ACTION_USB_PERMISSION).apply { `package` = packageName }
        val pi = PendingIntent.getBroadcast(this, 0, permIntent, PendingIntent.FLAG_MUTABLE)
        registerReceiver(permReceiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        Log.d(TAG, "Requesting USB permission from activity context")
        usbManager.requestPermission(device, pi)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            try {
                unregisterReceiver(permReceiver)
            } catch (_: Exception) {
            }
        }
    }

    private val permReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context,
                intent: Intent,
            ) {
                receiverRegistered = false
                unregisterReceiver(this)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    Log.i(TAG, "USB permission granted")
                    val device = intentDevice(intent)
                    device?.let { GestureAccessibilityService.instance?.openCamera(it) }
                } else {
                    Log.w(
                        TAG,
                        "USB permission denied — if no dialog appeared, another app " +
                            "(e.g. Control Glasses) is the default handler for this device. " +
                            "Fix: Settings → Apps → Control Glasses → Open by default → " +
                            "Clear defaults, then unplug/replug glasses and pick xrdroiddesk.",
                    )
                    GestureAccessibilityService.instance?.onUsbPermissionDenied()
                }
                finish()
            }
        }

    companion object {
        private const val TAG = "GrantUsbPermActivity"
        const val ACTION_USB_PERMISSION = "com.repudi8or.xrdroiddesk.USB_PERMISSION"

        fun intentDevice(intent: Intent): UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
    }
}
