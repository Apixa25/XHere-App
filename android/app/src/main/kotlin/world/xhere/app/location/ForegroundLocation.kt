package world.xhere.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * The only location permissions XHere requests in M3.
 * Background location is PLAN M13 and stays opt-in. Do not add it here.
 */
object ForegroundLocation {
    val permissions: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    init {
        check(permissions.none { it == Manifest.permission.ACCESS_BACKGROUND_LOCATION }) {
            "XHere must not request background location"
        }
    }

    fun isGranted(context: Context): Boolean =
        permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
