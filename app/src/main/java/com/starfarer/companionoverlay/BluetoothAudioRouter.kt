package com.starfarer.companionoverlay

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Routes audio input to a connected Bluetooth headset using
 * AudioManager.setCommunicationDevice() (API 31+).
 *
 * Call [routeToBluetoothHeadset] before starting any recording,
 * and [clearRouting] when recording is done.
 *
 * When a BT headset with HFP 1.6+ is connected, the SCO link
 * negotiates mSBC (16kHz wideband). Older headsets fall back to
 * CVSD (8kHz). The AudioRecord sample rate should match what the
 * link actually provides — check AudioRecord.getSampleRate() after
 * initialization if you need to verify.
 */
class BluetoothAudioRouter(private val context: Context) {

    companion object {
        private const val TAG = "BtAudioRouter"
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var routedDevice: AudioDeviceInfo? = null

    /**
     * Find a connected BT headset and set it as the communication device.
     * Returns true if a device was found and set, false otherwise.
     */
    fun routeToBluetoothHeadset(): Boolean {
        val devices = audioManager.availableCommunicationDevices

        // Prefer BLE headset, fall back to classic BT SCO
        val btDevice = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        if (btDevice == null) {
            DebugLog.log(TAG, "No BT headset in available communication devices. Types: ${
                devices.map { deviceTypeName(it.type) }
            }")
            return false
        }

        val result = audioManager.setCommunicationDevice(btDevice)
        if (result) {
            routedDevice = btDevice
            DebugLog.log(TAG, "Routed to ${btDevice.productName} (${deviceTypeName(btDevice.type)})")
        } else {
            DebugLog.log(TAG, "setCommunicationDevice failed for ${btDevice.productName}")
        }
        return result
    }

    /**
     * Clear the communication device routing, returning to system default.
     */
    fun clearRouting() {
        if (routedDevice != null) {
            audioManager.clearCommunicationDevice()
            DebugLog.log(TAG, "Cleared BT audio routing")
            routedDevice = null
        }
    }

    /**
     * Whether we're currently routing to a BT headset.
     */
    val isRouted: Boolean get() = routedDevice != null

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        else -> "TYPE_$type"
    }
}
