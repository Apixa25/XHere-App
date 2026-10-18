package world.xhere.app.config

import world.xhere.app.BuildConfig
import world.xhere.shared.SupabaseConfig

/**
 * Public client config. [supabase] is [SupabaseConfig.PLACEHOLDER] until
 * `SUPABASE_URL` and `SUPABASE_ANON_KEY` are set. The service-role key is
 * never part of this object.
 *
 * [serverReady] and [googleReady] are overridable so UI tests can talk to
 * [world.xhere.shared.data.fake.InMemoryXHere] without a project.
 */
data class ClientConfig(
    val supabase: SupabaseConfig,
    val googleWebClientId: String,
    val serverReady: Boolean = supabase.isConfigured,
    val googleReady: Boolean = serverReady && isRealGoogleClientId(googleWebClientId),
) {
    override fun toString(): String =
        "ClientConfig(url=${supabase.url}, serverReady=$serverReady, googleReady=$googleReady)"

    companion object {
        fun fromBuildConfig(): ClientConfig {
            val parsed = SupabaseConfig(
                url = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
            )
            val supabase = if (parsed.isConfigured) parsed else SupabaseConfig.PLACEHOLDER
            return ClientConfig(
                supabase = supabase,
                googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
            )
        }
    }
}

internal fun isRealGoogleClientId(value: String): Boolean =
    value.isNotBlank() && !value.startsWith("your-")
