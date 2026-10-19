package world.xhere.app.location

import android.location.Location
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.shared.integrity.LocationSignal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationSignalTest {
    private val now = Instant.parse("2026-10-18T04:00:00Z")

    @Test
    fun mockLocationAndDeveloperOptionsBecomeACoarseSignal() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.Global.putInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        assertTrue(developerOptionsEnabled(context))

        val location = Location("gps").apply {
            latitude = 40.0
            longitude = -100.0
            accuracy = 12f
            time = now.toEpochMilliseconds()
            isMock = true
        }
        val reading = location.toReading(developerOptions = true, now = now)
        assertEquals(40.0, reading.point.latitude)
        assertTrue(reading.signal.mock)
        assertTrue(reading.signal.developerOptions)
        assertTrue(reading.signal.deviceFix)
        assertEquals(12.0, reading.signal.accuracyM)
        assertEquals(now, reading.signal.fixAt)
        assertEquals(now, reading.signal.deviceAt)
    }

    @Test
    fun aNormalFixIsNotMarkedMock() {
        val location = Location("gps").apply {
            latitude = 42.0
            longitude = -79.0
            time = now.toEpochMilliseconds()
            isMock = false
        }
        val signal: LocationSignal = location.toReading(developerOptions = false, now = now).signal
        assertFalse(signal.mock)
        assertFalse(signal.developerOptions)
        assertTrue(signal.deviceFix)
    }
}
