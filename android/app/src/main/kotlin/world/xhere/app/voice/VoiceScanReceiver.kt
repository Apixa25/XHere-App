package world.xhere.app.voice

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.koin.core.context.GlobalContext

/**
 * PendingIntent target for the filtered background scan.
 * Exported is false. The only extra it reads is the scan result list.
 */
class VoiceScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val radio = runCatching { GlobalContext.get().get<VoiceRadio>() }.getOrNull() ?: return
        scanResults(intent).flatMap(::packetsOf).forEach(radio::offer)
    }

    private fun scanResults(intent: Intent): List<ScanResult> {
        val listed = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        }
        return listed.orEmpty()
    }
}
