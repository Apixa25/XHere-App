package world.xhere.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLinksManifestTest {
    @Test
    fun httpsDropLinkResolvesToMainActivity() {
        assertTrue(resolves("https://xhere.world/d/JmtwnHist01a"))
        assertTrue(resolves("https://xhere.world/d/JmtwnInc03cd"))
    }

    @Test
    fun otherHostsAndPathsDoNotResolve() {
        assertFalse(resolves("https://example.com/d/JmtwnHist01a"))
        assertFalse(resolves("http://xhere.world/d/JmtwnHist01a"))
        assertFalse(resolves("https://xhere.world/privacy"))
        assertFalse(resolves("https://xhere.world/delete-account"))
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
}
