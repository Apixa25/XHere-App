package world.xhere.shared.validation

/**
 * Translates the POSIX regular-expression subset stored in `content_filters.pattern`
 * into a Kotlin [Regex]. Postgres matches these with `~*` (case-insensitive).
 *
 * The taxonomy uses character classes (`[[:alnum:]]`, `[[:alpha:]]`) and word edges
 * (`\m`, `\M`). Anything else is left as-is. If the result does not compile, [compile]
 * returns null and the server filter still runs inside `create_drop`.
 */
object PosixPattern {
    fun compile(posix: String): Regex? {
        val kotlin = translate(posix)
        return try {
            Regex(kotlin, RegexOption.IGNORE_CASE)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun matches(posix: String, text: String): Boolean = compile(posix)?.containsMatchIn(text) == true

    /**
     * POSIX classes may sit inside a larger bracket expression
     * (`[[:alnum:]._%+-]`). Replacing the whole `[[:alnum:]]` token would close
     * that class early and leave the extra characters outside it.
     */
    fun translate(posix: String): String {
        val out = StringBuilder(posix.length)
        var i = 0
        var inClass = false
        var escaped = false
        while (i < posix.length) {
            val c = posix[i]
            if (escaped) {
                out.append(c)
                escaped = false
                i++
                continue
            }
            if (c == '\\') {
                if (!inClass && (posix.startsWith("\\m", i) || posix.startsWith("\\M", i))) {
                    out.append("\\b")
                    i += 2
                    continue
                }
                out.append(c)
                escaped = true
                i++
                continue
            }
            if (c == '[' && !inClass) {
                inClass = true
                out.append(c)
                i++
                continue
            }
            if (c == ']' && inClass) {
                inClass = false
                out.append(c)
                i++
                continue
            }
            val klass = posixClassAt(posix, i)
            if (klass != null) {
                if (!inClass) out.append('[')
                out.append(klass.body)
                if (!inClass) out.append(']')
                i += klass.length
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private data class PosixClass(val body: String, val length: Int)

    private fun posixClassAt(text: String, index: Int): PosixClass? {
        if (!text.startsWith("[:", index)) return null
        val close = text.indexOf(":]", index + 2)
        if (close < 0) return null
        val name = text.substring(index + 2, close)
        val body = when (name) {
            "alnum" -> "0-9A-Za-z"
            "alpha" -> "A-Za-z"
            "digit" -> "0-9"
            "space" -> "\\s"
            else -> return null
        }
        return PosixClass(body, close + 2 - index)
    }
}
