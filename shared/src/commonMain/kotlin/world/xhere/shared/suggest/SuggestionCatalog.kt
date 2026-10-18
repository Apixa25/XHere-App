package world.xhere.shared.suggest

/**
 * Words the ranker embeds for each built-in category.
 * The label and description match `20261018000600_taxonomy.sql`.
 * The hints match `20261018000700_suggestions.sql` and
 * `supabase/functions/_shared/catalog.ts`. Keep the three copies together.
 */
internal data class CatalogEntry(
    val slug: String,
    val label: String,
    val description: String,
    val hints: String,
) {
    fun document(): String = document(slug, label, description, hints)

    companion object {
        fun document(slug: String, label: String, description: String, hints: String): String =
            listOf(slug.replace('_', ' '), label, description, hints).joinToString(" ")
    }
}

internal object SuggestionCatalog {
    val entries: List<CatalogEntry> = listOf(
        CatalogEntry("historical_fact", "Historical fact", "Something that happened here, ideally with a source.", "history historical museum happened year source archive"),
        CatalogEntry("story", "Story", "A personal or local story tied to this spot.", "story tale personal memory anecdote grandmother"),
        CatalogEntry("memorial", "Memorial", "Remembering a person or event connected to this place.", "memorial plaque remember remembrance candle"),
        CatalogEntry("viewpoint", "Viewpoint", "A view worth stopping for.", "viewpoint view overlook vista scenic lake panorama overlook scenic vista"),
        CatalogEntry("trail_note", "Trail note", "Conditions, turns, and tips on a trail.", "trail hike path muddy turn conditions hiking"),
        CatalogEntry("wildlife", "Wildlife sighting", "An animal or plant seen here recently.", "wildlife animal bird owl deer fox plant sighting owl owl"),
        CatalogEntry("water_source", "Water source", "Drinking fountains, springs, and refill spots.", "drinking fountain spring refill water bottle tap"),
        CatalogEntry("food_truck", "Food truck", "A truck or cart serving here right now.", "food truck cart taco street kitchen trailer lunch vehicle"),
        CatalogEntry("eatery_tip", "Eatery tip", "What to order, when to go, what to skip.", "restaurant cafe diner menu order dish plate eatery"),
        CatalogEntry("market", "Market", "Farmers markets, night markets, and stalls.", "farmers market stall produce vendor bazaar"),
        CatalogEntry("event", "Event", "Something happening here at a set time.", "event festival concert fair schedule happening show"),
        CatalogEntry("popup", "Pop-up", "A short-lived pop-up shop, show, or stand.", "popup temporary shop stand kiosk"),
        CatalogEntry("yard_sale", "Yard sale", "A yard, garage, or stoop sale.", "yard sale garage sale stoop"),
        CatalogEntry("for_sale", "For sale", "One item for sale, picked up here.", "for sale item pickup price selling"),
        CatalogEntry("free_stuff", "Free stuff", "Curb alerts and giveaways.", "free stuff giveaway curb couch freebie"),
        CatalogEntry("hazard", "Hazard", "Road, sidewalk, or flood hazards.", "hazard flood pothole sidewalk closed ice road warning"),
        CatalogEntry("safety_alert", "Safety alert", "Something people nearby should know about now.", "safety alert warning caution danger nearby"),
        CatalogEntry("incident_report", "Incident report", "A crime or incident that happened nearby. Approximate location only.", "incident crime theft break-in robbery police report"),
        CatalogEntry("place_of_worship", "Place of worship", "Churches, mosques, temples, synagogues, and more.", "church mosque temple synagogue worship service faith"),
        CatalogEntry("community_space", "Community space", "Libraries, centers, gardens, and halls open to the public.", "library community center hall garden public room"),
        CatalogEntry("notice_board", "Notice", "A community notice for people nearby.", "notice announcement board flyer community"),
        CatalogEntry("infrastructure", "Infrastructure", "Bridges, utilities, and how the city works.", "bridge utility sewer infrastructure works"),
        CatalogEntry("construction", "Construction", "What is being built here, and until when.", "construction crane crew building site roadwork scaffold"),
        CatalogEntry("underground", "Underground", "Tunnels, buried rivers, and what lies beneath.", "tunnel underground buried railroad underpass subway"),
        CatalogEntry("hidden_gem", "Hidden gem", "Easy to walk past. Worth stopping for.", "hidden gem courtyard secret alley easy to miss"),
        CatalogEntry("tip", "Local tip", "Something only locals know about this spot.", "local tip locals know shortcut advice"),
        CatalogEntry("street_art", "Street art", "Murals, graffiti, and paste-ups.", "mural graffiti street art wall painting paste"),
        CatalogEntry("public_art", "Public art", "Sculptures, installations, and monuments.", "monument sculpture statue installation bronze public art"),
        CatalogEntry("music_spot", "Music spot", "Buskers, open mics, and great acoustics.", "music busker guitar open mic acoustic singer"),
        CatalogEntry("note", "Note", "Anything else worth knowing about this spot.", "note miscellaneous other general"),
    )

    fun entry(slug: String): CatalogEntry? = entries.firstOrNull { it.slug == slug }

    fun document(slug: String, label: String, description: String?): String {
        val known = entry(slug)
        if (known != null) return known.document()
        return CatalogEntry.document(slug, label, description.orEmpty(), "")
    }
}
