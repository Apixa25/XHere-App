package world.xhere.shared.data.supabase

import android.content.Context
import com.russhwolf.settings.SettingsInitializer

/**
 * Auth saves the session with multiplatform-settings. On a device
 * `SettingsInitializer` runs from the manifest before
 * [android.app.Application.onCreate]. Robolectric often skips that provider,
 * and [SupabaseXHereClient] then fails while building the default session store.
 * Call this first.
 *
 * supabase-kt's own initializer is internal and only needed when an OAuth
 * redirect opens a browser. Email OTP and the Google ID token do not use it.
 * The manifest provider still runs it on a device.
 */
fun prepareSupabaseSessionStore(context: Context) {
    SettingsInitializer().create(context)
}
