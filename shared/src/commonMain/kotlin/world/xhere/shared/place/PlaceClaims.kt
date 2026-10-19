package world.xhere.shared.place

import world.xhere.shared.model.PlaceHours

/**
 * Free claim rules. The database copies in
 * `supabase/migrations/20261021140000_claims_and_capsules.sql` are the source
 * of truth. Paid plans are a catalog row the activator refuses. This object
 * does not bill anyone.
 */
object PlaceClaims {
    const val ONSITE_M: Double = 50.0
    const val PLACE_RADIUS_M: Double = 80.0
    const val BADGE: String = "Official"
    const val FREE_PLAN: String = "free"

    val FREE_MAIL: Set<String> = setOf(
        "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com", "msn.com",
        "yahoo.com", "ymail.com", "icloud.com", "me.com", "mac.com", "aol.com",
        "proton.me", "protonmail.com", "gmx.com", "mail.com", "zoho.com", "yandex.com",
    )

    /** Host of an https URL, with a leading www removed. Null when it is not a website. */
    fun websiteHost(url: String?): String? {
        val raw = url?.trim()?.lowercase().orEmpty()
        if (!raw.startsWith("https://")) return null
        var host = raw.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore(':')
        if (host.startsWith("www.")) host = host.removePrefix("www.")
        if (!HOST.matches(host)) return null
        return host
    }

    fun emailDomain(email: String?): String? {
        val raw = email?.trim()?.lowercase().orEmpty()
        val at = raw.lastIndexOf('@')
        if (at <= 0 || at == raw.lastIndex - 1) return null
        return raw.substring(at + 1)
    }

    fun freeMail(domain: String?): Boolean = domain?.lowercase() in FREE_MAIL

    /** Exact host match. A free-mail inbox never proves a website. */
    fun domainsMatch(email: String?, website: String?): Boolean {
        val host = websiteHost(website) ?: return false
        val domain = emailDomain(email) ?: return false
        if (freeMail(domain)) return false
        return domain == host
    }

    fun hoursOk(hours: List<PlaceHours>): Boolean {
        if (hours.size > 14) return false
        return hours.all { row ->
            row.day in 1..7 && CLOCK.matches(row.open) && CLOCK.matches(row.close) && row.close > row.open
        }
    }

    private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
    private val CLOCK = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
}
