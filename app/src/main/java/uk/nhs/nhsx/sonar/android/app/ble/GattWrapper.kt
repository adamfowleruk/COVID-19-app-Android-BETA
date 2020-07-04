/*
 * Copyright © 2020 NHSX. All rights reserved.
 */

package uk.nhs.nhsx.sonar.android.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.net.MacAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import uk.nhs.nhsx.sonar.android.app.crypto.BluetoothIdProvider
import uk.nhs.nhsx.sonar.android.app.crypto.BluetoothIdentifier
import kotlin.random.Random

class GattWrapper(
    private val server: BluetoothGattServer?,
    private val coroutineScope: CoroutineScope,
    private val bluetoothManager: BluetoothManager,
    private val bluetoothIdProvider: BluetoothIdProvider,
    private val keepAliveCharacteristic: BluetoothGattCharacteristic,
    private val scanner: Scanner,
    private val randomValueGenerator: () -> ByteArray = { Random.nextBytes(1) }
) {
    var notifyJob: Job? = null

    private val subscribedDevices = mutableListOf<BluetoothDevice>()
    private val lock = Mutex()

    private val payload: ByteArray
        get() = bluetoothIdProvider.provideBluetoothPayload().asBytes()

    private val payloadIsValid: Boolean
        get() = bluetoothIdProvider.canProvideIdentifier()

    // af-13 - Write characteristic support
    private val writtenIds = HashMap<String,ByteArray>() // MacAddress String to Byte Array

    fun respondToCharacteristicRead(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        // af-08 Additional logging
        Timber.d("Received characteristic read from ${device.address} for char ${characteristic.uuid}")
        if (characteristic.isKeepAlive()) {
            Timber.d("Was a read of the keepalive. Returning empty success response")
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(1)) // af-05 added some output for keepalive read
            return
        }

        if (characteristic.isDeviceIdentifier()) {
            if (payloadIsValid) {
                Timber.d("Was a read of my Bluetooth ID. Returning payload to ${device.address}")
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, payload)

                return
            } else {
                Timber.d("Was a read of the Bluetooth ID - but we don't have a valid payload! Returning empty success")
                // af-05 sending a success but no value so that connection isn't terminated by an 'error'
                // The broadcast ID will be picked up again when it's next requested (every 16 seconds on iOS currently)
                server?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    byteArrayOf()
                )
                return
            }
        }

        if (characteristic.isNearbyDescriptor()) {
            Timber.d("nearby - characteristic being read")
            var requestorMac = device.address
            Timber.d("nearby - Returning a list of nearby devices by their most recent data shared to $requestorMac")
            var writtenAny = false
            for (key in writtenIds.keys) {
                if (!requestorMac.equals(key)) {
                    // send a response
                    Timber.d("nearby - Found a recent key that isn't the requestor, so sending: $key")
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, writtenIds[key])
                    writtenAny = true
                    break // af-13 TODO only ever send one for now - change in future
                }
            }
            if (!writtenAny) {
                Timber.d("Not seen any other nearby IDs. Sending successful blank response")
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf())
            }

            return
        }

        Timber.d("Unknown read for characteristic with id (not nearby): ${characteristic.uuid}")
    }

    fun respondToCharacteristicWrite(device:BluetoothDevice?, characteristic:BluetoothGattCharacteristic?, offset: Int, value: ByteArray?, responseNeeded: Boolean, requestId: Int) {
        Timber.d("Nearby ID Write request received")
        if (null == device || null == characteristic || null == value) {
            return
        }
        // TODO don't assume it's the ID Char that is being written
        // If its the ID characteristic, read the data, and cache against the sender's MAC address
        Timber.d("nearby - received write request from mac ${device.address} - caching bytes array")
        writtenIds[device.address] = value

        // af-14 MUST SEND RESPONSE FOR iOS
        server?.sendResponse(device,requestId,BluetoothGatt.GATT_SUCCESS,0,byteArrayOf())

        // TODO clear out writtenIds every so often (E.g. if older than a minute)

        // TODO check the written value isn't our own ID, or a recent old one

        // TODO af-18 Log remote written IDs as contacts
        val identifier = BluetoothIdentifier.fromBytes(value)
        scanner.storeEvent(identifier,10,coroutineScope,10)
        // TODO use correct power/rssi settings in the above in future
    }

    fun respondToDescriptorWrite(
        device: BluetoothDevice?,
        descriptor: BluetoothGattDescriptor?,
        responseNeeded: Boolean,
        requestId: Int
    ) {
        // af-08 Additional logging
        Timber.d("Received descriptor write") // just in case below optional causes it not to log
        Timber.d("Received descriptor write from ${device?.address}")


        if (device == null ||
            descriptor == null ||
            !descriptor.isNotifyDescriptor() ||
            !(descriptor.characteristic.isKeepAlive())
        ) {
            Timber.d("Sending empty response to descriptor write")
            if (responseNeeded)
                server?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS, // af-05 sending success but blank value so connection isn't terminated
                    0,
                    byteArrayOf()
                )
            return
        }

        // Will be setting up a separate job later on
        if (descriptor.characteristic.isDeviceIdentifier()) {
            // af-08
            Timber.d("Device $device characteristic for subscription is BluetoothID - should we skip?")
            //return
        }





        // af-15 IMPORTANT NEVER EVER EVER REMOVE THE FOLLOWING LINE - IT WILL CAUSE IOS TO BLOCK UNTIL A RESPONSE OR 30s AND THE CONNECTION DIES
        // send response
        Timber.d("Sending response to descriptor write (nearby id descriptor)")
        server?.sendResponse(device,requestId,BluetoothGatt.GATT_SUCCESS,0, byteArrayOf())






        Timber.d("Device $device has subscribed to general keep alive.")
        // AF af-12 YES - should we just have this job running anyway???
        coroutineScope.launch {
            Timber.d("Ensuring notify job is started")
            if (null == notifyJob) {
                notifyJob = notifyKeepAliveSubscribersPeriodically(coroutineScope)
            }
            Timber.d("LOCK fetching subscribers lock")
            lock.withLock {
                //if (subscribedDevices.isEmpty()) {
                // af-12 changing logic here, as we should always have this job going
                subscribedDevices.add(device)
                Timber.d("LOCK relinquishing subscribers lock")
            }
        }
    }

    private fun notifyKeepAliveSubscribersPeriodically(coroutineScope: CoroutineScope) =
        coroutineScope.launch {
            // af-08 Additional logging
            Timber.d("Just launched task to notify subscribers")
            while (isActive) {
                delay(1_000) // af-17 dropped this to one to see the effect when it runs in the BG
                keepAliveCharacteristic.value = randomValueGenerator()
                val connected = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)

                // af-12 minimising lock time here to prevent contention
                Timber.d("LOCK fetching subscribers lock")
                lock.withLock {
                    val connectedSubscribers = connected.intersect(subscribedDevices)

                    Timber.d("Alerting connected subscribers... (if any) count: ${connectedSubscribers.count()}")

                    connectedSubscribers.forEach {
                        Timber.d("Sending keepalive notification to ${it.address}")
                        server?.notifyCharacteristicChanged(it, keepAliveCharacteristic, false)
                    }
                    Timber.d("LOCK relinquishing subscribers lock")
                }
            }
        }

    fun deviceDisconnected(device: BluetoothDevice?) {
        if (device == null) return
        // af-08 Additional logging
        Timber.d("Device disconnected from us ${device.address}")

        coroutineScope.launch {
            Timber.d("LOCK fetching subscribers lock")
            lock.withLock {
                if (subscribedDevices.isEmpty()) {
                    Timber.d("LOCK relinquishing subscribers lock")
                    return@launch
                }
                subscribedDevices.remove(device)
                // af-12 Removing this so we always fire this job every 8 seconds, to avoid delay on reconnect and resubscribe
                /*
                if (subscribedDevices.isEmpty()) {
                    Timber.d("Terminating notify job")
                    notifyJob?.cancel()
                }
                 */
                Timber.d("LOCK relinquishing subscribers lock")
            }
        }
    }
}
