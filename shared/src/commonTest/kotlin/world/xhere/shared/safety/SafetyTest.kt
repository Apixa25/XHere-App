package world.xhere.shared.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.UserTier

class SafetyTest {
    private val store = JamestownDemo.store()
    private val incident = checkNotNull(store.taxonomy.category("incident_report"))
    private val alert = checkNotNull(store.taxonomy.category("safety_alert"))
    private val hazard = checkNotNull(store.taxonomy.category("hazard"))

    @Test
    fun jamestownGetsThePoliceTipLineAndElsewhereOnly911() {
        val here = SafetyDirectory.forLocation(JamestownDemo.center)
        assertEquals("(716) 483-8477", here.tipLine?.phone)
        assertEquals("tel:7164838477", here.tipLine?.dialUri)
        assertEquals(
            "Have information about a crime? Jamestown Police anonymous tip line (716) 483-8477 · Emergency 911.",
            here.footer,
        )
        val buffalo = SafetyDirectory.forLocation(LatLng(42.886, -78.878))
        assertNull(buffalo.tipLine)
        assertEquals("In an emergency call 911.", buffalo.footer)
        assertNull(SafetyDirectory.forLocation(null).tipLine)
    }

    @Test
    fun incidentReportsAreOnlyOfferedWhereATipLineExists() {
        val buffalo = SafetyDirectory.forLocation(LatLng(42.886, -78.878))
        assertTrue(SafetyFlow.availableAt(incident, SafetyDirectory.chautauqua))
        assertFalse(SafetyFlow.availableAt(incident, buffalo))
        assertTrue(SafetyFlow.availableAt(alert, buffalo))
        assertTrue(SafetyFlow.appliesTo(incident) && SafetyFlow.appliesTo(alert))
        assertFalse(SafetyFlow.appliesTo(hazard))
    }

    @Test
    fun newUsersSafetyAlertsAreHeldAndTrustedIncidentReportsAreNot() {
        assertTrue(SafetyFlow.willBeHeld(alert, UserTier.NEW))
        assertFalse(SafetyFlow.willBeHeld(alert, UserTier.REGULAR))
        assertTrue(SafetyFlow.willBeHeld(incident, UserTier.REGULAR))
        assertFalse(SafetyFlow.willBeHeld(incident, UserTier.TRUSTED))
    }

    @Test
    fun filterHitNamesTheFirstMatchingFilter() {
        val filters = store.taxonomy.filters
        assertEquals("person_descriptors", SafetyFlow.filterHit(incident, filters, listOf("Suspect ran", null))?.slug)
        assertEquals("street_address", SafetyFlow.filterHit(incident, filters, listOf("Break-in", "at 45 W 3rd Street"))?.slug)
        assertEquals("contact_info", SafetyFlow.filterHit(alert, filters, listOf("Call 716-555-0100 now"))?.slug)
        assertNull(SafetyFlow.filterHit(alert, filters, listOf("Power out", "at 45 W 3rd Street")), "alerts don't use the address filter")
        assertNull(SafetyFlow.filterHit(incident, filters, listOf("Window broken on Main Street around 2 am")))
        assertNull(SafetyFlow.filterHit(incident, filters, listOf("  ", null)))
    }
}
