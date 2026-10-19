package world.xhere.app.voice

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import world.xhere.shared.voice.VoiceProtocol

/**
 * Phone radio for Everything Has a Voice.
 * Foreground scans use a callback. Background scans use a PendingIntent and
 * the three filters in [VoiceProtocol.backgroundFilters]. Advertising is
 * non-connectable and stops when this process stops. There is no foreground service.
 */
class AndroidVoiceRadio(
    private val context: Context,
    private val preferences: VoicePreferences,
    private val battery: VoiceBattery,
) : VoiceRadio {
    private val _packets = MutableSharedFlow<HeardPacket>(extraBufferCapacity = 64)
    override val packets: SharedFlow<HeardPacket> = _packets.asSharedFlow()
    private val _advertising = MutableStateFlow<String?>(null)
    override val advertisingVoiceId: StateFlow<String?> = _advertising.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            packetsOf(result).forEach(::offer)
        }

        override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>) {
            results.flatMap(::packetsOf).forEach(::offer)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            _advertising.value = null
        }
    }

    override fun offer(packet: HeardPacket) {
        _packets.tryEmit(packet)
    }

    @SuppressLint("MissingPermission")
    override fun foreground(mode: VoiceScanMode) {
        stopBackground()
        val scanner = scanner() ?: return
        runCatching { scanner.stopScan(scanCallback) }
        val settings = ScanSettings.Builder()
            .setScanMode(
                if (mode == VoiceScanMode.LOW_LATENCY) {
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                } else {
                    ScanSettings.SCAN_MODE_BALANCED
                },
            )
            .build()
        runCatching { scanner.startScan(filters(), settings, scanCallback) }
    }

    @SuppressLint("MissingPermission")
    override fun stopForeground() {
        val scanner = scanner() ?: return
        runCatching { scanner.stopScan(scanCallback) }
    }

    @SuppressLint("MissingPermission")
    override fun background(enabled: Boolean) {
        val scanner = scanner() ?: return
        val pending = backgroundIntent() ?: return
        runCatching { scanner.stopScan(pending) }
        if (!enabled || !preferences.background.value || !battery.allowsBackground()) return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        runCatching { scanner.startScan(filters(), settings, pending) }
    }

    @SuppressLint("MissingPermission")
    override fun advertise(voiceId: String) {
        if (!VoiceProtocol.isVoiceId(voiceId)) return
        val advertiser = advertiser() ?: return
        runCatching { advertiser.stopAdvertising(advertiseCallback) }
        val uuid = ParcelUuid(UUID.fromString(VoiceProtocol.SERVICE_UUID))
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(uuid)
            .addServiceData(uuid, VoiceProtocol.compactServiceData(voiceId))
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val started = runCatching {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        }.isSuccess
        _advertising.value = if (started) voiceId else null
    }

    @SuppressLint("MissingPermission")
    override fun stopAdvertising() {
        val advertiser = advertiser()
        if (advertiser != null) runCatching { advertiser.stopAdvertising(advertiseCallback) }
        _advertising.value = null
    }

    private fun stopBackground() {
        val scanner = scanner() ?: return
        val pending = backgroundIntent() ?: return
        runCatching { scanner.stopScan(pending) }
    }

    private fun filters(): List<ScanFilter> {
        val xhere = ParcelUuid(UUID.fromString(VoiceProtocol.SERVICE_UUID))
        val eddystone = ParcelUuid(UUID.fromString(VoiceProtocol.EDDYSTONE_UUID))
        return listOf(
            ScanFilter.Builder().setServiceData(xhere, ByteArray(0), ByteArray(0)).build(),
            ScanFilter.Builder().setServiceData(eddystone, ByteArray(0), ByteArray(0)).build(),
            ScanFilter.Builder()
                .setManufacturerData(
                    VoiceProtocol.APPLE_COMPANY_ID,
                    byteArrayOf(0x02, 0x15),
                    byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
                )
                .build(),
        )
    }

    private fun backgroundIntent(): PendingIntent? = runCatching {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, VoiceScanReceiver::class.java),
            flags,
        )
    }.getOrNull()

    private fun scanner(): BluetoothLeScanner? = runCatching {
        val adapter = manager()?.adapter ?: return null
        if (!adapter.isEnabled) return null
        adapter.bluetoothLeScanner
    }.getOrNull()

    private fun advertiser(): BluetoothLeAdvertiser? = runCatching {
        val adapter = manager()?.adapter ?: return null
        if (!adapter.isEnabled) return null
        adapter.bluetoothLeAdvertiser
    }.getOrNull()

    private fun manager(): BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
}
