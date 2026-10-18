package world.xhere.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.app.location.ForegroundLocation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundLocationManifestTest {
    @Test
    fun contract_is_fine_and_coarse_only() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            ForegroundLocation.permissions.toList(),
        )
        assertFalse(ForegroundLocation.permissions.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    @Test
    fun manifest_does_not_request_background_location() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val names = info.requestedPermissions?.toSet().orEmpty()
        assertTrue(Manifest.permission.INTERNET in names)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in names)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in names)
        assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in names)
    }
}
