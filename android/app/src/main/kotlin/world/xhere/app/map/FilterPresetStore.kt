package world.xhere.app.map

import android.content.Context

data class FilterPreset(
    val name: String,
    val slugs: List<String>,
    val builtIn: Boolean,
)

/**
 * Device-local filter presets. "Weekend" is built in (PLAN §4.4): Events, Food, and Sales.
 * Custom names live in SharedPreferences. This is not the v1.2 offline map cache.
 */
class FilterPresetStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<FilterPreset> = listOf(WEEKEND) + custom()

    fun save(name: String, slugs: List<String>): Boolean {
        val clean = name.trim().replace("|", "").take(MAX_NAME)
        if (clean.isEmpty() || clean.equals(WEEKEND.name, ignoreCase = true)) return false
        if (slugs.isEmpty()) return false
        val next = custom().filterNot { it.name.equals(clean, ignoreCase = true) } +
            FilterPreset(clean, slugs, builtIn = false)
        prefs.edit().putStringSet(KEY, next.map { encode(it) }.toSet()).apply()
        return true
    }

    private fun custom(): List<FilterPreset> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { decode(it) }.sortedBy { it.name }

    companion object {
        const val PREFS = "xhere_filter_presets"
        private const val KEY = "custom"
        private const val MAX_NAME = 24

        /** PLAN §4.4: "Weekend" is Events + Food + Sales. The RPC accepts these group slugs. */
        val WEEKEND = FilterPreset(
            name = "Weekend",
            slugs = listOf("events", "food_drink", "sales_free"),
            builtIn = true,
        )

        private fun encode(preset: FilterPreset): String = preset.name + "|" + preset.slugs.joinToString(",")

        private fun decode(raw: String): FilterPreset? {
            val split = raw.indexOf('|')
            if (split <= 0) return null
            val name = raw.substring(0, split).trim()
            val slugs = raw.substring(split + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (name.isEmpty() || slugs.isEmpty()) return null
            return FilterPreset(name, slugs, builtIn = false)
        }
    }
}
