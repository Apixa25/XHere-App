package world.xhere.app.ar

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The install contract: ARCore is optional and the APK carries no Geospatial key. */
class ArManifestTest {
    @Test
    fun arcore_is_optional_and_keyless() {
        val xml = File("src/main/AndroidManifest.xml").readText()
        assertTrue(xml.contains("android.hardware.camera.ar"))
        assertTrue(xml.contains("android:required=\"false\""))
        assertTrue(xml.contains("android:name=\"com.google.ar.core\""))
        assertTrue(xml.contains("android:value=\"optional\""))
        assertFalse(xml.contains("com.google.android.ar.API_KEY"))
        assertFalse(xml.contains("AIza"))
        assertFalse(xml.contains("apps.googleusercontent.com"))
    }
}
