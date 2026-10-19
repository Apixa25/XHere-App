package world.xhere.app.voice

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/** Background scans pause in power-save mode and while the battery is at 15% or below. */
class VoiceBattery(private val context: Context) {
    fun allowsBackground(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.isPowerSaveMode == true) return false
        val battery = context.getSystemService(BatteryManager::class.java) ?: return true
        val capacity = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return capacity !in 0..15
    }
}
