package world.xhere.shared.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import world.xhere.shared.data.fake.InMemoryXHere

class JamestownDemoTest {
    @Test
    fun downtownViewportExcludesTheLakeAndBuffalo() = runTest {
        val drops = client().drops.dropsInView(JamestownDemo.downtown, zoom = JamestownDemo.STREET_ZOOM)
        val titles = drops.map { it.title }
        assertTrue("Fenton Mansion" in titles)
        assertTrue("West 3rd at lunch" in titles)
        assertTrue(titles.none { it == "Lake view from Celoron" })
        assertTrue(titles.none { it == "Steps at Niagara Square" })
        assertTrue(drops.all { it.kind == "drop" && it.groupSlug != null })
    }

    @Test
    fun groupFilterIsAppliedInsideTheRepository() = runTest {
        val food = client().drops.dropsInView(
            JamestownDemo.downtown,
            categories = listOf("food_drink"),
            zoom = JamestownDemo.STREET_ZOOM,
        )
        assertTrue(food.isNotEmpty())
        assertTrue(food.all { it.groupSlug == "food_drink" })
        assertTrue(food.none { it.title == "Fenton Mansion" })
    }

    @Test
    fun categorySlugFilterAndTeaserHideTheBody() = runTest {
        val facts = client().drops.dropsInView(
            JamestownDemo.downtown,
            categories = listOf("historical_fact"),
            zoom = JamestownDemo.STREET_ZOOM,
        )
        assertEquals(listOf("Fenton Mansion"), facts.map { it.title })
        val teaser = client().drops.dropTeaser("fenton")
        assertEquals(false, teaser.revealed)
        assertNull(teaser.body)
        assertEquals("Fenton Mansion", teaser.title)
    }

    @Test
    fun buffaloBoxReturnsOnlyThatDropAndLowZoomClustersDowntown() = runTest {
        val away = client().drops.dropsInView(JamestownDemo.buffalo, zoom = JamestownDemo.STREET_ZOOM)
        assertEquals(listOf("Steps at Niagara Square"), away.map { it.title })
        val clusters = client().drops.dropsInView(JamestownDemo.downtown, zoom = JamestownDemo.CLUSTER_ZOOM)
        assertEquals(1, clusters.size)
        assertEquals("cluster", clusters.first().kind)
        assertEquals(6, clusters.first().pointCount)
    }

    @Test
    fun nearbyIsDistanceSortedAndStaysInsideTheRadius() = runTest {
        val near = client().drops.dropsNearby(JamestownDemo.center, radiusM = 5_000)
        assertEquals("West 3rd at lunch", near.first().title)
        assertTrue(near.none { it.title == "Steps at Niagara Square" })
        assertTrue(near.zipWithNext().all { (a, b) -> (a.distanceM ?: 0.0) <= (b.distanceM ?: 0.0) })
        assertTrue(near.any { it.title == "Lake view from Celoron" })
    }

    private fun client() = InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer)
}
