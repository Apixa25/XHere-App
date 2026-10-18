package world.xhere.shared.suggest

import kotlin.math.sqrt

/**
 * 384-dimension feature hash, the same steps as `private.hash_embed` and
 * `supabase/functions/_shared/embed.ts`.
 *
 * CI and the offline app use this instead of downloading gte-small weights.
 * The Edge Function calls gte-small only when `XHERE_EMBEDDING_MODEL=gte-small`
 * and the category rows were embedded with that same model. Mixing the two
 * spaces would make pgvector cosine meaningless, so the default stays `hash-v1`.
 */
internal object HashEmbedding {
    const val DIMENSIONS: Int = 384
    private const val FNV_OFFSET = 2166136261L
    private const val FNV_PRIME = 16777619L
    private const val MASK_32 = 0xffffffffL

    fun embed(text: String): DoubleArray {
        val vector = DoubleArray(DIMENSIONS)
        for (token in tokenize(text)) {
            val hash = fnv1a(token)
            val index = (hash % DIMENSIONS).toInt()
            val sign = if (((hash shr 9) and 1L) == 1L) 1.0 else -1.0
            vector[index] += sign
        }
        var sum = 0.0
        for (value in vector) sum += value * value
        val norm = sqrt(sum)
        if (norm == 0.0) return vector
        for (i in vector.indices) vector[i] /= norm
        return vector
    }

    fun cosine(left: DoubleArray, right: DoubleArray): Double {
        var sum = 0.0
        val n = minOf(left.size, right.size)
        for (i in 0 until n) sum += left[i] * right[i]
        return sum
    }

    fun tokenize(text: String): List<String> {
        val normalized = text.lowercase().replace('_', ' ').replace('-', ' ')
        return normalized.split(Regex("[^a-z0-9]+")).mapNotNull { part ->
            if (part.length < 2) return@mapNotNull null
            if (part.length > 4 && part.endsWith("s") && !part.endsWith("ss")) part.dropLast(1) else part
        }
    }

    private fun fnv1a(token: String): Long {
        var hash = FNV_OFFSET
        for (byte in token.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash = (hash * FNV_PRIME) and MASK_32
        }
        return hash and MASK_32
    }
}
