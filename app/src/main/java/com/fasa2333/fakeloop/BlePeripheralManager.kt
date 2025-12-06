package com.fasa2333.fakeloop

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.*

private const val TAG = "BlePeripheralManager"

class BlePeripheralManager(private val context: Context) {
    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    private var gattServer: BluetoothGattServer? = null
    // Track whether we are currently advertising to prevent duplicate starts
    @Volatile
    private var advertisingActive: Boolean = false

    // UUIDs for the service and characteristics (using 16-bit FFF0/FFF1/FFF2 expanded to 128-bit)
    private val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val CHAR_NOTIFY_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val CHAR_RW_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

    private val subscribedDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    fun isAdvertising(): Boolean = advertisingActive

    fun isPeripheralSupported(): Boolean {
        return bluetoothAdapter?.isMultipleAdvertisementSupported == true && bluetoothAdapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun startPeripheral() {
        // Prevent duplicate peripheral/start calls if already advertising
        if (advertisingActive) {
            Log.i(TAG, "startPeripheral called but advertising already active; ignoring")
            return
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or disabled")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "Device does not support BLE advertising (peripheral mode)")
            return
        }

        startGattServer()
        startAdvertising()
    }

    @SuppressLint("MissingPermission")
    fun stopPeripheral() {
        stopAdvertising()
        stopGattServer()
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        // If GATT server is already open, don't recreate it
        if (gattServer != null) {
            Log.i(TAG, "GATT server already started; skipping startGattServer")
            return
        }
        val manager = bluetoothManager ?: return
        gattServer = manager.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            return
        }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val charNotify = BluetoothGattCharacteristic(
            CHAR_NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        charNotify.addDescriptor(cccd)

        val charRw = BluetoothGattCharacteristic(
            CHAR_RW_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(charNotify)
        service.addCharacteristic(charRw)

        gattServer?.clearServices()
        gattServer?.addService(service)
        Log.i(TAG, "GATT server started with service fff0 and characteristics fff1/fff2")
    }

    @SuppressLint("MissingPermission")
    private fun stopGattServer() {
        gattServer?.close()
        gattServer = null
        subscribedDevices.clear()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        advertiser ?: return

        // Guard: do not start advertising if already active
        if (advertisingActive) {
            Log.i(TAG, "startAdvertising called but advertisingActive=true; skipping")
            return
        }

        // Mark advertising as in-progress to prevent duplicate start attempts while the stack begins advertising
        advertisingActive = true

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // The requested raw packet (hex):
        // 02 01 02 0B 09 4C 52 30 32 39 34 32 39 42 44 0B FF EE 00 4C 4F 4F 50 00 02 00 0C 03 03 F0 FF
        // We will include the device name (0x09), set manufacturer data with company id 0x00EE and payload
        // matching the original manufacturer payload (4C 4F 4F 50 00 02 0x00), and add the 16-bit service UUID 0xFFF0.

        val advertiseDataBuilder = AdvertiseData.Builder()
        advertiseDataBuilder.setIncludeDeviceName(true)

        // Original manufacturer AD in the provided packet uses company id 0x00EE and payload bytes:
        // 4C 4F 4F 50 00 02 00  (ASCII "L O O P" + 0x00 0x02 0x00)
        val manufPayloadHex = "4C4F4F50000200"
        val manufPayload = hexStringToByteArray(manufPayloadHex)
        val companyId = 0x00EE
        advertiseDataBuilder.addManufacturerData(companyId, manufPayload)

        // Also add the 16-bit service UUID 0xFFF0 so GATT clients can discover the service from advertisement
        advertiseDataBuilder.addServiceUuid(ParcelUuid(SERVICE_UUID))

        val advertiseData = advertiseDataBuilder.build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, advertiseCallback)
            Log.i(TAG, "Started advertising (build attempt compatible with Android AdvertiseData)")
        } catch (ex: Exception) {
            advertisingActive = false
            Log.e(TAG, "startAdvertising exception: ${ex.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertisingActive = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            advertisingActive = true
            Log.i(TAG, "Advertise start success: $settingsInEffect")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            advertisingActive = false
            Log.e(TAG, "Advertise start failed: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.i(TAG, "GATT connection state changed: ${device.address} -> $newState (status=$status)")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedDevices.remove(device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            if (descriptor?.uuid?.toString()?.equals("00002902-0000-1000-8000-00805f9b34fb", ignoreCase = true) == true && device != null) {
                val enabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                if (enabled) {
                    subscribedDevices.add(device)
                    Log.i(TAG, "Device subscribed for notifications: ${device.address}")
                } else {
                    subscribedDevices.remove(device)
                    Log.i(TAG, "Device unsubscribed for notifications: ${device.address}")
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else {
                if (responseNeeded && device != null) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (device == null || characteristic == null) return
            val value = characteristic.value ?: byteArrayOf(0x00)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            if (device == null || characteristic == null) return
            Log.i(TAG, "Write to characteristic ${characteristic.uuid} from ${device.address}, value=${value?.joinToString(",")}")
            // Store the value in the characteristic
            if (value != null) {
                characteristic.value = value
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun notifySubscribers(data: ByteArray) {
        val service = gattServer?.getService(SERVICE_UUID) ?: run {
            Log.e(TAG, "Service not found when notifying")
            return
        }
        val char = service.getCharacteristic(CHAR_NOTIFY_UUID)
        if (char == null) {
            Log.e(TAG, "Notify characteristic not found")
            return
        }
        char.value = data
        val devicesCopy = ArrayList(subscribedDevices)
        for (device in devicesCopy) {
            val ok = gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
            Log.i(TAG, "Notified ${device.address} ok=$ok")
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
