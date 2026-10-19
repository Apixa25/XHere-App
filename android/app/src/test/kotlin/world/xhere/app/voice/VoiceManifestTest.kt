package world.xhere.app.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceManifestTest {
    @Test
    fun microphoneIsRequestedButNotRequired() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val names = info.requestedPermissions?.toSet().orEmpty()
        assertTrue(Manifest.permission.RECORD_AUDIO in names)
        val required = info.reqFeatures.orEmpty().any { feature ->
            feature.name == "android.hardware.microphone" &&
                feature.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED != 0
        }
        assertFalse(required)
    }

    @Test
    fun bluetoothScanIsNeverForLocationAndTheReceiverIsPrivate() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val names = info.requestedPermissions?.toList().orEmpty()
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in names)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in names)
        val manifest = mergedManifest()
        val scan = manifest.substringAfter("android.permission.BLUETOOTH_SCAN")
        assertTrue(scan.substringBefore("/>").contains("neverForLocation"))
        val receiver = context.packageManager.getReceiverInfo(
            android.content.ComponentName(context, VoiceScanReceiver::class.java),
            0,
        )
        assertFalse(receiver.exported)
    }

    @Test
    fun thingLinksResolveAndPrivacyDoesNot() {
        assertTrue(resolves("https://xhere.world/t/0123456789ab"))
        assertTrue(resolves("xhere://t/0123456789ab"))
        assertFalse(resolves("https://xhere.world/privacy"))
        assertFalse(resolves("https://example.com/t/0123456789ab"))
    }

    private fun resolves(url: String): Boolean {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val matches = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
        return matches.any { it.activityInfo.name == "world.xhere.app.MainActivity" }
    }

    /** Robolectric does not copy usesPermissionFlags into requestedPermissionsFlags. */
    private fun mergedManifest(): String {
        val candidates = listOf(
            File("build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"),
            File("android/app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"),
        )
        return candidates.first { it.isFile }.readText()
    }
}
