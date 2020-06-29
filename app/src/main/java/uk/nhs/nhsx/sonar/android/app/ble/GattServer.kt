/*
 * Copyright © 2020 NHSX. All rights reserved.
 */

package uk.nhs.nhsx.sonar.android.app.ble

import android.bluetooth.*
import android.bluetooth.BluetoothGattCharacteristic.*
import android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
import android.content.Context
import com.polidea.rxandroidble2.RxBleDevice
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber
import uk.nhs.nhsx.sonar.android.app.crypto.BluetoothIdProvider
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

    fun start(coroutineScope: CoroutineScope) {
        Timber.d("Bluetooth Gatt start")
        val callback = object : BluetoothGattServerCallback() {
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                Timber.d("onCharacteristicReadRequest received for ${characteristic.uuid}")
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
                Timber.d("onCharacteristicWriteRequest received")
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
                Timber.d("  Connecting    : ${BluetoothProfile.STATE_CONNECTING}")
                Timber.d("  Connected     : ${BluetoothProfile.STATE_CONNECTED}")
                Timber.d("  Disconnecting : ${BluetoothProfile.STATE_DISCONNECTING}")
                Timber.d("  Disconnected  : ${BluetoothProfile.STATE_DISCONNECTED}")
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
            keepAliveCharacteristic
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
