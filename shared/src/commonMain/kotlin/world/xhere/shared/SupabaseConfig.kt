package world.xhere.shared

/**
 * Public Supabase client config. [anonKey] is the publishable (anon) key from
 * `.env.example` (`SUPABASE_ANON_KEY`). Never put the service-role key in the app.
 *
 * [PLACEHOLDER] matches `.env.example`. The hosted project is
 * `jpeuldzegtbqfezijhuy` (PLAN §8). Calls with the placeholder fail at the
 * network until `SUPABASE_URL` and `SUPABASE_ANON_KEY` are set outside git.
 * `SUPABASE_ANON_KEY` is the publishable key (`sb_publishable_…`).
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
