/*
 * Copyright © 2020 NHSX. All rights reserved.
 */

package uk.nhs.nhsx.sonar.android.app.ble

import android.os.ParcelUuid
import android.util.Base64
import com.polidea.rxandroidble2.LogConstants
import com.polidea.rxandroidble2.LogOptions
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanResult
import com.polidea.rxandroidble2.scan.ScanSettings
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.functions.BiFunction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import timber.log.Timber
import uk.nhs.nhsx.sonar.android.app.BuildConfig
import uk.nhs.nhsx.sonar.android.app.crypto.BluetoothIdentifier
import uk.nhs.nhsx.sonar.android.app.di.module.BluetoothModule
import javax.inject.Inject
import javax.inject.Named

class Scanner @Inject constructor(
    private val rxBleClient: RxBleClient,
    private val saveContactWorker: SaveContactWorker,
    private val eventEmitter: BleEventEmitter,
    private val currentTimestampProvider: () -> DateTime = { DateTime.now(DateTimeZone.UTC) },
    @Named(BluetoothModule.SCAN_INTERVAL_LENGTH)
    private val scanIntervalLength: Int,
    base64Decoder: (String) -> ByteArray = { Base64.decode(it, Base64.DEFAULT) },
    val base64Encoder: (ByteArray) -> String = { Base64.encodeToString(it, Base64.DEFAULT) }
) {

    private var knownDevices: MutableMap<String, BluetoothIdentifier> = mutableMapOf()
    private var devices: MutableList<ScanResult> = mutableListOf()

    private val sonarServiceUuidFilter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid(SONAR_SERVICE_UUID))
        .build()

    private var scanDisposable: Disposable? = null
    private var scanJob: Job? = null
    private val appleManufacturerId = 76
    private val encodedBackgroundIosServiceUuid: ByteArray =
        base64Decoder(BuildConfig.SONAR_ENCODED_BACKGROUND_IOS_SERVICE_UUID)

    /*
     When the iPhone app goes into the background iOS changes how services are advertised:
  
         1) The service uuid is now null
         2) The information to identify the service is encoded into the manufacturing data in a
         unspecified/undocumented way.
  
        The below filter is based on observation of the advertising packets produced by an iPhone running
        the app in the background.
       */
    private val sonarBackgroundedIPhoneFilter = ScanFilter.Builder()
        .setServiceUuid(null)
        .setManufacturerData(
            appleManufacturerId,
            encodedBackgroundIosServiceUuid
        )
        .build()

    private val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .build()

    fun start(coroutineScope: CoroutineScope) {
        val logOptions = LogOptions.Builder()
            .setLogLevel(LogConstants.DEBUG)
            .setLogger { level, tag, msg -> Timber.tag(tag).log(level, msg) }
            .build()
        RxBleClient.updateLogOptions(logOptions)

        scanJob = coroutineScope.launch {
            while (isActive) {

                Timber.d("scan - Starting")
                devices.clear() // TODO af-13 after - do this clearing more intelligently (i.e. if not seen in X minutes)
                scanDisposable = scan() // launches the scan, asynchronously

                delay(1000) // naive artificial delay for scan

                //Timber.d("scan - Stopping (should have taken 1 second)")


                if (!isActive) return@launch

                // Some devices are unable to connect while a scan is running
                // or just after it finished
                // af-13 only do this if we've seen any - makes scanning a tony bit
                // more aggressive (a good thing)
                if (devices.count() > 0) {
                    // af-13 async so no point waiting if gt zero
                    //Timber.d("1 second scan delay post scan starts...: ")
                    //delay(1_000) // to allow devices to be ready
                    //Timber.d("1 second scan delay post scan ends...: ")

                    Timber.d("scan - Discovered this many devices: " + devices.count())
                    Timber.d("scan - Calling connectToEachDiscoveredDevice")
                    connectToEachDiscoveredDevice(coroutineScope)
                    //delay(7_000) // af-05 to bypass undocumented scan throttle

                    // af-05 - will remove devices in a separate thread before they can be connected to!
                    // af-05 now we have an 8 seconds delay, added clear back in
                } else {
                    Timber.d("scan - no devices found, skipping connect stage")
                }


                // af-13 delay moved to end of activity

                // af-05 the below is superfluous, and just causes scanning to be denied through attempting it too often (< once per 6s)
                //var attempts = 0
                /*
                while (attempts++ < 10 && devices.isEmpty()) {
                    if (!isActive) {
                        Timber.d("scan - Stopping in loop")
                        disposeScanDisposable()
                        return@launch
                    }
                    */
                // af-10 extra logging around delays to see if that affects entire BLe subsystem on Android
                Timber.d("scan - interval = " + scanIntervalLength.toLong())
                Timber.d("Standard interval scan delay starts...: ")
                delay((scanIntervalLength.toLong() - 1 )* 1_000) // naive delay between scan attempts. Minus the 1 second delay from above
                Timber.d("Standard interval scan delay ends...: ")

                //}
                // af-13 dispose of this in our max time (i.e. full 8 second delay)
                disposeScanDisposable()

            }
        }
    }

    fun stop() {
        disposeScanDisposable()
        scanJob?.cancel()
    }

    private fun connectToEachDiscoveredDevice(coroutineScope: CoroutineScope) {
        // af-05 changed the below to be unique by macAddress of the device, to bypass any weirdness in comparators
        devices.distinctBy { it.bleDevice.macAddress }.forEach { scanResult ->
            val macAddress = scanResult.bleDevice.macAddress
            Timber.d("Evaluating scan result $macAddress")
            val device = scanResult.bleDevice
            val identifier = knownDevices[macAddress]
            val txPowerAdvertised = scanResult.scanRecord.txPowerLevel

            // AF NOT REQUIRED now we're scanning every second we need a sensible delay for connecting per device to prevent over connecting
            // af-05 - upped scanning to every 8 seconds as it finished in 250ms and is reliable, and there's an undocumented scan limit!

            val operation = if (identifier != null) {
                readOnlyRssi(identifier)
            } else {
                readIdAndRssi()
            }

            Timber.d("Connecting to $macAddress")
            connectAndPerformOperation(
                device,
                macAddress,
                txPowerAdvertised,
                coroutineScope,
                operation
            )
        }
    }

    private fun readIdAndRssi(): (RxBleConnection) -> Single<Pair<ByteArray, Int>> =
        { connection ->
            negotiateMTU(connection)
                .flatMap {
                    disableRetry(it)
                }
                .flatMap {
                    Single.zip(
                        // TODO validate that doing both of these at the same time doesn't break one or the other
                        it.readCharacteristic(SONAR_IDENTITY_CHARACTERISTIC_UUID),
                        it.readRssi(),
                        BiFunction<ByteArray, Int, Pair<ByteArray, Int>> { characteristicValue, rssi ->
                            Timber.d("read - ID and $rssi for ${base64Encoder(characteristicValue)}")
                            characteristicValue to rssi
                        }
                    )
                }
        }

    private fun readOnlyRssi(identifier: BluetoothIdentifier): (RxBleConnection) -> Single<Pair<ByteArray, Int>> =
        { connection: RxBleConnection ->
            connection.readRssi().map { rssi ->
                Timber.d("read - only rssi $rssi for ${base64Encoder(identifier.cryptogram.asBytes())}")
                identifier.asBytes() to rssi
            }
        }

    private fun connectAndPerformOperation(
        device: RxBleDevice,
        macAddress: String,
        txPowerAdvertised: Int,
        coroutineScope: CoroutineScope,
        readOperation: (RxBleConnection) -> Single<Pair<ByteArray, Int>>
    ) {
        val compositeDisposable = CompositeDisposable()
        device
            .establishConnection(false)
            .flatMapSingle {
                Timber.d("Connected to $macAddress")
                readOperation(it)
            }
            .doOnSubscribe {
                compositeDisposable.add(it)
            }
            .take(1)
            .blockingSubscribe(
                { (identifier, rssi) ->
                    onReadSuccess(
                        identifier,
                        rssi,
                        compositeDisposable,
                        macAddress,
                        txPowerAdvertised,
                        coroutineScope
                    )
                },
                { e -> onReadError(e, compositeDisposable, macAddress) }
            )
    }

    private fun disposeScanDisposable() {
        scanDisposable?.dispose()
        scanDisposable = null
    }

    private fun scan(): Disposable? =
        rxBleClient
            .scanBleDevices(
                settings,
                sonarBackgroundedIPhoneFilter,
                sonarServiceUuidFilter
            )
            .subscribe(
                {
                    Timber.d("scan - found = ${it.bleDevice.macAddress}")
                    devices.add(it)
                },
                ::scanError
            )

    private fun disableRetry(connection: RxBleConnection): Single<RxBleConnection> =
        connection.queue { bluetoothGatt, _, _ ->
            DisableRetryOnUnauthenticatedRead.bypassAuthenticationRetry(
                bluetoothGatt
            )
            Observable.just(connection)
        }.firstOrError()

    private fun onReadSuccess(
        identifierBytes: ByteArray,
        rssi: Int,
        connectionDisposable: CompositeDisposable,
        macAddress: String,
        txPowerAdvertised: Int,
        scope: CoroutineScope
    ) {
        // AF TODO evaluate if the below actually stops discovery by killing the connection too early (i.e. if only one read has occurred)
        connectionDisposable.dispose()
        if (identifierBytes.size != BluetoothIdentifier.SIZE) {
            throw IllegalArgumentException("Identifier has wrong size, must be ${BluetoothIdentifier.SIZE}, was ${identifierBytes.size}")
        }
        val identifier = BluetoothIdentifier.fromBytes(identifierBytes)
        updateKnownDevices(identifier, macAddress)
        Timber.d(
            "seen MAC $macAddress as ${base64Encoder(identifier.cryptogram.asBytes())
                .drop(2)
                .take(12)}"
        )
        storeEvent(identifier, rssi, scope, txPowerAdvertised)
    }

    private fun onReadError(
        e: Throwable,
        connectionDisposable: CompositeDisposable,
        macAddress: String
    ) {
        // AF should we disconnect, or just re-try the read? - af-10 not ever seeing this happen in logs
        connectionDisposable.dispose()
        Timber.e("failed reading from $macAddress - $e")
        eventEmitter.errorEvent(macAddress, e)
    }

    private fun updateKnownDevices(identifier: BluetoothIdentifier, macAddress: String) {
        val previousMac = knownDevices.entries.firstOrNull { (_, v) ->
            v.cryptogram.asBytes().contentEquals(identifier.cryptogram.asBytes())
        }
        knownDevices.remove(previousMac?.key)
        knownDevices[macAddress] = identifier
        Timber.d("Previous MAC was ${previousMac?.key}, new is $macAddress")
    }

    private fun negotiateMTU(connection: RxBleConnection): Single<RxBleConnection> {
        // AF TODO verify that the below doesn't cause a connection to fail
        // the overhead appears to be 2 bytes
        return connection.requestMtu(2 + BluetoothIdentifier.SIZE)
            .doOnSubscribe { Timber.d("Negotiating MTU started") }
            .doOnError { e: Throwable? ->
                Timber.e("Failed to negotiate MTU: $e")
                Observable.error<Throwable?>(e)
            }
            .doOnSuccess { Timber.d("Negotiated MTU: $it") }
            .ignoreElement()
            .andThen(Single.just(connection))
    }

    private fun scanError(e: Throwable) {
        Timber.e("Scan failed with: $e")
    }

    private fun storeEvent(
        identifier: BluetoothIdentifier,
        rssi: Int,
        scope: CoroutineScope,
        txPowerAdvertised: Int
    ) {
        eventEmitter.successfulContactEvent(
            identifier,
            listOf(rssi),
            txPowerAdvertised
        )

        saveContactWorker.createOrUpdateContactEvent(
            scope,
            identifier,
            rssi,
            currentTimestampProvider(),
            txPowerAdvertised
        )
    }
}
