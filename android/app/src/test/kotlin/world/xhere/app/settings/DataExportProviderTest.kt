package world.xhere.app.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Download my data shares one file through a private FileProvider (M7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataExportProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theExportProviderIsPrivateAndGrantsPerShare() {
        val authority = "${context.packageName}.exports"
        val providers = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()),
        ).providers.orEmpty()
        val provider = providers.firstOrNull { it.authority == authority }
        assertNotNull(provider)
        assertFalse(provider!!.exported)
        assertTrue(provider.grantUriPermissions)
    }

    @Test
    fun onlyTheExportsFolderMapsToAContentUri() {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "xhere-export-2026-10-18.json").apply { writeText("{}") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        assertEquals("content", uri.scheme)
        assertTrue(uri.path!!.endsWith("/exports/xhere-export-2026-10-18.json"))
        val outside = File(context.filesDir, "secret.json").apply { writeText("{}") }
        assertTrue(runCatching { FileProvider.getUriForFile(context, "${context.packageName}.exports", outside) }.isFailure)
    }
}
