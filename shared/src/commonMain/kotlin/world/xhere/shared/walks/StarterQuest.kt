package world.xhere.shared.walks

import kotlin.uuid.Uuid

/**
 * The global starter quest. Same id as the row inserted by
 * `20261018235000_walks_and_quests.sql`. region is null on the server:
 * every region offers it.
 */
object StarterQuest {
    val ID: Uuid = Uuid.parse("a0000000-0000-4000-8000-000000000004")
    const val SLUG: String = "starter"
    const val TITLE: String = "Start here"
    const val DESCRIPTION: String = "Visit 3 places, confirm 1 drop, and make your first drop."
}
