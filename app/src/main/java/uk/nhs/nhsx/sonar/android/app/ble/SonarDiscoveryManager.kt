package uk.nhs.nhsx.sonar.android.app.ble

import timber.log.Timber

interface KeepaliveSourceListener {
    fun keepalive()
}

// af-18
class SonarDiscoveryManager  private constructor() : KeepaliveSourceListener {
    var scanner: Scanner? = null
    var bluetoothService: BluetoothService? = null

    private object HOLDER {
        val INSTANCE = SonarDiscoveryManager()
    }

    companion object {
        val instance: SonarDiscoveryManager by lazy { HOLDER.INSTANCE }
    }

    override fun keepalive() {
        // Do sensible checking here
        healthCheck()
    }

    private fun healthCheck() {
        Timber.d("Keepalive listener healthcheck called")
        bluetoothService?.ensureGattAdvertisingIsRunning()
    }
}