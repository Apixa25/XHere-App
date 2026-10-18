package world.xhere.shared

/**
 * Public Supabase client config. [anonKey] is the publishable (anon) key from
 * `.env.example` (`SUPABASE_ANON_KEY`). Never put the service-role key in the app.
 *
 * [PLACEHOLDER] matches the example file. No project has been created; calls with
 * it fail at the network, which is expected until Steven approves a free Supabase
 * account (PLAN §8).
 */
data class SupabaseConfig(
    val url: String,
    val anonKey: String,
) {
    val isConfigured: Boolean
        get() = url.startsWith("https://") &&
            !url.contains("your-project-ref") &&
            anonKey.isNotBlank() &&
            anonKey != "your-anon-key"

    companion object {
        val PLACEHOLDER: SupabaseConfig = SupabaseConfig(
            url = "https://your-project-ref.supabase.co",
            anonKey = "your-anon-key",
        )
    }
}
