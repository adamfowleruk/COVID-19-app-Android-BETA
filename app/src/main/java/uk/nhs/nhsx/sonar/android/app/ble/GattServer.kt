/*
 * Copyright © 2020 NHSX. All rights reserved.
 */

package uk.nhs.nhsx.sonar.android.app.ble

import android.app.Activity
import android.bluetooth.*
import android.bluetooth.BluetoothGattCharacteristic.*
import android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
import android.bluetooth.BluetoothProfile.GATT
import android.bluetooth.BluetoothProfile.GATT_SERVER
import android.content.Context
import android.content.Intent
import com.polidea.rxandroidble2.RxBleDevice
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber
import uk.nhs.nhsx.sonar.android.app.crypto.BluetoothIdProvider
import uk.nhs.nhsx.sonar.android.app.onboarding.PermissionActivity
import javax.inject.Inject


class GattServer @Inject constructor(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val bluetoothIdProvider: BluetoothIdProvider,
    private val scanner: Scanner
) {
    private val keepAliveCharacteristic = BluetoothGattCharacteristic(
        SONAR_KEEPALIVE_CHARACTERISTIC_UUID,
        PROPERTY_READ + PROPERTY_NOTIFY,
        PERMISSION_READ
    ).also {
        it.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG,
                PERMISSION_READ + PERMISSION_WRITE // Needs write so we can subscribe
            )
        )
    }

    private val identityCharacteristic = BluetoothGattCharacteristic(
        SONAR_IDENTITY_CHARACTERISTIC_UUID,
        PROPERTY_READ ,
        PERMISSION_READ
    ).also {
        it.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG,
                PERMISSION_READ
            )
        )
    }

    private val nearbyCharacteristic = BluetoothGattCharacteristic(
        SONAR_NEARBY_IDENTITY_CHARACTERISTIC_UUID,
        PROPERTY_READ + PROPERTY_WRITE,
        PERMISSION_READ + PERMISSION_WRITE
    ).also {
        it.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG,
                PERMISSION_READ + PERMISSION_WRITE
            )
        )
    }

    private val service: BluetoothGattService =
        BluetoothGattService(SONAR_SERVICE_UUID, SERVICE_TYPE_PRIMARY)
            .also {
                it.addCharacteristic(
                    identityCharacteristic
                )
                it.addCharacteristic(
                    nearbyCharacteristic
                )
                it.addCharacteristic(
                    keepAliveCharacteristic
                )
            }

    private var server: BluetoothGattServer? = null
    private var gattWrapper: GattWrapper? = null

    fun ensureRunning(coroutineScope: CoroutineScope) {
        // af-18
        if (bluetoothManager.adapter.state == BluetoothAdapter.STATE_OFF) {
            Timber.d("ALERT ALERT ADAPTER IS IN OFF STATE AWOOOOGAAAAAAA!!!")
            bluetoothManager.adapter.enable() // requires admin privileges - naughty... try it though as we should already have this permission if running
            /*
            startActivityForResult(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                PermissionActivity.REQUEST_ENABLE_BT
            )*/
            //start(coroutineScope) - note: according to bluetooth spec this should restart in the previous state
        }

        Timber.d("ensureRunning - printing current connection statuses")
        printConnectionInfo()
    }

    fun printConnectionInfo() {
        if (null == server) return
        for (dev in bluetoothManager.getConnectedDevices(GATT_SERVER)) {
            Timber.d("CONN device ${dev.address} (profile=GATT_SERVER) has connection state: ${bluetoothManager.getConnectionState(dev,GATT_SERVER)}")
        }
        for (dev in bluetoothManager.getConnectedDevices(GATT)) {
            Timber.d("CONN device ${dev.address} (profile=GATT) has connection state: ${bluetoothManager.getConnectionState(dev,GATT)}")
        }
    }

    fun start(coroutineScope: CoroutineScope) {
        Timber.d("Bluetooth Gatt start")
        val callback = object : BluetoothGattServerCallback() {
            // af-33 log everything for maximum visibility of underlying bluetooth activity
            // logging overrides
            override fun onDescriptorReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor?
            ) {
                Timber.d("CONN onDescriptorReadRequest")
                Timber.d("CONN onDescriptorReadRequest for ${device?.uuids}")
                super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            }

            override fun onExecuteWrite(
                device: BluetoothDevice?,
                requestId: Int,
                execute: Boolean
            ) {
                Timber.d("CONN onExecuteWrite")
                Timber.d("CONN onExecuteWrite for ${device?.uuids}")
                super.onExecuteWrite(device, requestId, execute)
            }

            override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
                Timber.d("CONN onMtuChanged")
                Timber.d("CONN onMtuChanged for ${device?.uuids}")
                super.onMtuChanged(device, mtu)
            }

            override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
                Timber.d("CONN onNotificationSent")
                Timber.d("CONN onNotificationSent for ${device?.uuids}")
                super.onNotificationSent(device, status)
            }

            override fun onPhyRead(device: BluetoothDevice?, txPhy: Int, rxPhy: Int, status: Int) {
                Timber.d("CONN onPhyRead called")
                Timber.d("CONN onPhyRead for ${device?.uuids}")
                super.onPhyRead(device, txPhy, rxPhy, status)
            }

            override fun onPhyUpdate(
                device: BluetoothDevice?,
                txPhy: Int,
                rxPhy: Int,
                status: Int
            ) {
                Timber.d("CONN onPhyUpdate")
                Timber.d("CONN onPhyUpdate for ${device?.uuids}")
                super.onPhyUpdate(device, txPhy, rxPhy, status)
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                Timber.d("CONN onServiceAdded")
                Timber.d("CONN onServiceAdded for ${service?.uuid}")
                super.onServiceAdded(status, service)
            }


            // functional sonar protocol overrides
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                Timber.d("CONN onCharacteristicReadRequest received for ${characteristic.uuid}")
                gattWrapper?.respondToCharacteristicRead(device, requestId, characteristic)
            }


            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                Timber.d("CONN onCharacteristicWriteRequest received")
                gattWrapper?.respondToCharacteristicWrite(device, characteristic, offset, value, responseNeeded, requestId)
            }


            override fun onConnectionStateChange(
                device: BluetoothDevice?,
                status: Int,
                newState: Int
            ) {
                // af-14 call moved to the end of the function as per article
                //super.onConnectionStateChange(device, status, newState)
                // af-08 Additional logging
                Timber.d("Connection state change recorded...")
                Timber.d("Connection state change for ${device?.address} was $status now $newState")
                Timber.d("  Connecting state constant    : ${BluetoothProfile.STATE_CONNECTING}")
                Timber.d("  Connected state constant     : ${BluetoothProfile.STATE_CONNECTED}")
                Timber.d("  Disconnecting state constant : ${BluetoothProfile.STATE_DISCONNECTING}")
                Timber.d("  Disconnected state constant  : ${BluetoothProfile.STATE_DISCONNECTED}")

                printConnectionInfo()

                // af-33 hack to prevent 20 minute android disconnecting wait time, and thus starvation of incoming BLe connections
                if (newState == BluetoothProfile.STATE_DISCONNECTING && null != device) {
                    Timber.d("CONN Forcing disconnect of disconnecting device")
                    Timber.d("CONN Forcing disconnect of disconnecting device ${device?.uuids}")
                    server?.cancelConnection(device)
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gattWrapper?.deviceDisconnected(device)
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && null != device) {
                    Timber.d("TODO Opening reply connection for incoming connected device")
                    //scanner.connectTo(coroutineScope,device.address,1)
                    // af-14 this also gets around one way detection with a backgrounded iPhone
                    /*
                    device.connectGatt(scanner.connectAndPerformOperation(
                        device,
                        device.macAddress,
                        1,
                        coroutineScope,
                        scanner.Read
                    ), false, this)
                    //scanner.connectTo(coroutineScope, device., 1) // TODO change this to something from connection, if present
                    */
                }
                super.onConnectionStateChange(device, status, newState)
            }

            // AF WHY IS THIS EVER NECESSARY??? We never receive a descriptor write, but perhaps, in future, a characteristic write
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                descriptor: BluetoothGattDescriptor?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                Timber.d("Descriptor write received. (subscribing for notify)")
                gattWrapper?.respondToDescriptorWrite(device, descriptor, responseNeeded, requestId)
            }
        }

        server = bluetoothManager.openGattServer(context, callback)
        server?.addService(service)


        gattWrapper = GattWrapper(
            server,
            coroutineScope,
            bluetoothManager,
            bluetoothIdProvider,
            keepAliveCharacteristic,
            scanner
        )
    }

    fun stop() {
        Timber.d("Bluetooth Gatt stop")
        server?.close()
        server = null
        gattWrapper?.notifyJob?.cancel()
        gattWrapper = null
    }
}
