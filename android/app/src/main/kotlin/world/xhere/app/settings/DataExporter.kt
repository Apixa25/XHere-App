package world.xhere.app.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Hands the `export_my_data` JSON to the system so the person can save or send it. */
fun interface DataExporter {
    fun export(fileName: String, json: String)
}

/**
 * Writes the file under `cacheDir/exports` (see `res/xml/export_paths.xml`) and
 * opens the share sheet, which includes "Save to Files"/Drive. Each export
 * replaces the previous file, so an old copy doesn't sit in the cache.
 */
class AndroidDataExporter(private val context: Context) : DataExporter {
    override fun export(fileName: String, json: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName).apply { writeText(json) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
