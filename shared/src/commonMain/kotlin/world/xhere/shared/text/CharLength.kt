package world.xhere.shared.text

/**
 * Unicode code points, matching Postgres `char_length` (not UTF-16 code units, and not
 * grapheme clusters). Title and body limits in `create_drop` use `char_length`.
 */
fun String.charLength(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        val code = this[index]
        index += if (code.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}
