package world.xhere.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import world.xhere.shared.model.MapFeature
import world.xhere.shared.model.Taxonomy

/** One bitmap per marker label, drawn with the category color from the taxonomy. */
internal object MarkerIcons {
    fun idFor(label: String): String = "xhere-" + label.hashCode().toUInt().toString(16)

    fun bitmap(label: String, stroke: Int, publicRecord: Boolean = false): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.42f, fill)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.42f, ring)
        if (publicRecord) {
            val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(size * 0.62f, size * 0.08f, size * 0.92f, size * 0.38f, 8f, 8f, mark)
        }
        val lines = label.split('\n')
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            textSize = if (lines.size > 1) 36f else 64f
        }
        val bounds = Rect()
        val lineHeight = text.textSize * 1.05f
        val start = size / 2f - lineHeight * (lines.size - 1) / 2f
        lines.forEachIndexed { index, line ->
            text.getTextBounds(line, 0, line.length, bounds)
            canvas.drawText(line, size / 2f, start + index * lineHeight - bounds.exactCenterY(), text)
        }
        return bitmap
    }

    fun strokeColor(feature: MapFeature, taxonomy: Taxonomy?): Int {
        val slug = feature.categorySlug
        val category = slug?.let { taxonomy?.category(it) }
        val groupSlug = feature.groupSlug
        val group = when {
            groupSlug != null -> taxonomy?.group(groupSlug)
            category != null -> taxonomy?.groups?.firstOrNull { it.id == category.groupId }
            else -> null
        }
        return parseHex(category?.color ?: group?.color) ?: Color.parseColor("#0F6E56")
    }

    fun parseHex(raw: String?): Int? {
        val hex = raw?.removePrefix("#") ?: return null
        if (hex.length != 6) return null
        val value = hex.toLongOrNull(16) ?: return null
        return (0xFF000000L or value).toInt()
    }
}
