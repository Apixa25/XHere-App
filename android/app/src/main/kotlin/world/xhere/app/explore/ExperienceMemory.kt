package world.xhere.app.explore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import world.xhere.shared.explore.ExperienceDismissal

private val Context.experienceDataStore by preferencesDataStore(name = "xhere_experiences")

/**
 * Device-local dismissals and the map-hint switch.
 * The undo row is memory only, so a restart keeps the dismissal and drops the snackbar.
 */
data class ExperienceLocal(
    val dismissals: Set<ExperienceDismissal> = emptySet(),
    val hintEnabled: Boolean = true,
    val pendingUndo: ExperienceDismissal? = null,
)

interface ExperienceMemory {
    val state: Flow<ExperienceLocal>

    suspend fun dismiss(id: String, revision: String)

    suspend fun undo(id: String)

    suspend fun clearUndo()

    /** Snackbar for a dismissal that lives in another store, such as a weather alert id. */
    suspend fun rememberUndo(item: ExperienceDismissal)

    suspend fun setHintEnabled(enabled: Boolean)

    suspend fun reset()
}

/** Used when a screen is built without the app graph. Tests that need persistence use [DataStoreExperienceMemory]. */
class MemoryExperienceStore : ExperienceMemory {
    private val local = MutableStateFlow(ExperienceLocal())
    override val state: Flow<ExperienceLocal> = local

    override suspend fun dismiss(id: String, revision: String) {
        val item = ExperienceDismissal(id, revision)
        local.update { current ->
            current.copy(
                dismissals = current.dismissals.filterNot { it.id == id }.toSet() + item,
                pendingUndo = item,
            )
        }
    }

    override suspend fun undo(id: String) {
        local.update { current ->
            current.copy(
                dismissals = current.dismissals.filterNot { it.id == id }.toSet(),
                pendingUndo = null,
            )
        }
    }

    override suspend fun clearUndo() {
        local.update { it.copy(pendingUndo = null) }
    }

    override suspend fun rememberUndo(item: ExperienceDismissal) {
        local.update { it.copy(pendingUndo = item) }
    }

    override suspend fun setHintEnabled(enabled: Boolean) {
        local.update { it.copy(hintEnabled = enabled) }
    }

    override suspend fun reset() {
        local.value = ExperienceLocal()
    }
}

class DataStoreExperienceMemory(context: Context) : ExperienceMemory {
    private val dataStore = context.applicationContext.experienceDataStore
    private val undo = MutableStateFlow<ExperienceDismissal?>(null)

    override val state: Flow<ExperienceLocal> = combine(
        dataStore.data.catch { emit(emptyPreferences()) },
        undo,
    ) { prefs, pending ->
        ExperienceLocal(
            dismissals = decode(prefs[DISMISSALS].orEmpty()),
            hintEnabled = prefs[HINT] ?: true,
            pendingUndo = pending,
        )
    }

    override suspend fun dismiss(id: String, revision: String) {
        val item = ExperienceDismissal(id, revision)
        dataStore.edit { prefs ->
            val next = decode(prefs[DISMISSALS].orEmpty()).filterNot { it.id == id } + item
            prefs[DISMISSALS] = next.map(::encode).toSet()
        }
        undo.value = item
    }

    override suspend fun undo(id: String) {
        dataStore.edit { prefs ->
            val next = decode(prefs[DISMISSALS].orEmpty()).filterNot { it.id == id }
            prefs[DISMISSALS] = next.map(::encode).toSet()
        }
        undo.value = null
    }

    override suspend fun clearUndo() {
        undo.value = null
    }

    override suspend fun rememberUndo(item: ExperienceDismissal) {
        undo.value = item
    }

    override suspend fun setHintEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[HINT] = enabled }
    }

    override suspend fun reset() {
        dataStore.edit { it.clear() }
        undo.value = null
    }

    private companion object {
        val DISMISSALS = stringSetPreferencesKey("dismissals")
        val HINT = booleanPreferencesKey("explore_hint")
        const val SEP = "\u001f"

        fun encode(item: ExperienceDismissal): String =
            item.id.replace(SEP, "") + SEP + item.revision.replace(SEP, "")

        fun decode(raw: Set<String>): Set<ExperienceDismissal> = raw.mapNotNull { row ->
            val split = row.indexOf(SEP)
            if (split <= 0 || split >= row.lastIndex) return@mapNotNull null
            ExperienceDismissal(row.substring(0, split), row.substring(split + 1))
        }.toSet()
    }
}
