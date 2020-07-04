/*
 * Copyright © 2020 NHSX. All rights reserved.
 */

package uk.nhs.nhsx.sonar.android.app.di.module

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import androidx.core.content.ContextCompat.getSystemService
import com.polidea.rxandroidble2.RxBleClient
import dagger.Module
import dagger.Provides
import uk.nhs.nhsx.sonar.android.app.BuildConfig
import uk.nhs.nhsx.sonar.android.app.ble.*
import uk.nhs.nhsx.sonar.android.app.util.DeviceDetection
import javax.inject.Named

@Module
open class BluetoothModule(
    private val applicationContext: Context,
    private val scanIntervalLength: Int
) {
    @Provides
    fun provideBluetoothManager(): BluetoothManager =
        getSystemService(applicationContext, BluetoothManager::class.java)!!

    @Provides
    fun provideBluetoothAdvertiser(bluetoothManager: BluetoothManager): BluetoothLeAdvertiser =
        bluetoothManager.adapter.bluetoothLeAdvertiser

    @Provides
    open fun provideRxBleClient(): RxBleClient =
        RxBleClient.create(applicationContext)

    @Provides
    open fun provideDeviceDetection(): DeviceDetection =
        DeviceDetection(BluetoothAdapter.getDefaultAdapter(), applicationContext)

    @Provides
    open fun provideScanner(
        rxBleClient: RxBleClient,
        saveContactWorker: SaveContactWorker,
        debugBleEventEmitter: DebugBleEventTracker,
        noOpBleEventEmitter: NoOpBleEventEmitter
    ): Scanner {
        val eventEmitter = when (BuildConfig.BUILD_TYPE) {
            "debug", "internal" -> debugBleEventEmitter
            else -> noOpBleEventEmitter
        }
        val scanner = Scanner(
            rxBleClient,
            saveContactWorker,
            eventEmitter,
            SonarDiscoveryManager.instance,
            scanIntervalLength = scanIntervalLength
        )
        SonarDiscoveryManager.instance.scanner = scanner
        return scanner
    }

    @Provides
    open fun provideKeepaliveListener(): KeepaliveSourceListener {
        return SonarDiscoveryManager.instance
    }

    @Provides
    open fun provideSonarManager(): SonarDiscoveryManager {
        return SonarDiscoveryManager.instance
    }

    @Provides
    @Named(SCAN_INTERVAL_LENGTH)
    fun provideScanIntervalLength() = scanIntervalLength

    companion object {
        const val SCAN_INTERVAL_LENGTH = "SCAN_INTERVAL_LENGTH"
    }
}
