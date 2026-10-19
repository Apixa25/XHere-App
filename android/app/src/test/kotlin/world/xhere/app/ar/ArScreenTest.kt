package world.xhere.app.ar

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import world.xhere.app.FreshSessionRule
import world.xhere.app.ar.ArAdvice
import world.xhere.app.MainActivity
import world.xhere.app.TestGraph
import world.xhere.app.TestXHereApplication
import world.xhere.app.ui.TestTags
import world.xhere.shared.ar.ArPose
import world.xhere.shared.geo.LatLng

/**
 * The camera session is the fake. These tests cover the button, the Google
 * notice, locked versus public text, and a Geospatial drop that counts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class ArScreenTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun arButtonStaysVisibleAndExplainsWhenGeospatialIsUnavailable() {
        TestGraph.ar.nextSupport = ArSupport.UNAVAILABLE
        TestGraph.location.setPermission(true)
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        rule.openMap()
        rule.onNodeWithTag(TestTags.MAP_AR).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.MAP_AR).performClick()
        rule.waitForTag(TestTags.AR_GUIDANCE)
        rule.onNodeWithTag(TestTags.AR_GUIDANCE).assertTextContains("not available at this spot", substring = true)
        rule.onNodeWithTag(TestTags.AR_PRIVACY).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.AR_CAMERA).assertDoesNotExist()
    }

    @Test
    fun arButtonExplainsAMissingInstallAnUnauthorizedBuildAndACheckThatIsStillRunning() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        TestGraph.ar.nextAdvice = ArAdvice.NEEDS_INSTALL
        rule.openMap()
        rule.onNodeWithTag(TestTags.MAP_AR).performClick()
        rule.waitForTag(TestTags.AR_GUIDANCE)
        rule.onNodeWithTag(TestTags.AR_GUIDANCE).assertTextContains("Play Services for AR", substring = true)

        TestGraph.ar.nextAdvice = ArAdvice.STILL_CHECKING
        rule.onNodeWithTag(TestTags.AR_GUIDANCE_ACTION).performClick()
        rule.waitForTag(TestTags.AR_GUIDANCE)
        rule.onNodeWithTag(TestTags.AR_GUIDANCE).assertTextContains("still checking", substring = true)

        TestGraph.ar.nextAdvice = ArAdvice.SERVICES_MISSING
        rule.onNodeWithTag(TestTags.AR_GUIDANCE_ACTION).performClick()
        rule.waitForTag(TestTags.AR_GUIDANCE)
        rule.onNodeWithTag(TestTags.AR_GUIDANCE).assertTextContains("cannot run", substring = true)
        rule.onNodeWithTag(TestTags.AR_GUIDANCE_ACTION).performClick()

        TestGraph.ar.nextAdvice = ArAdvice.NOT_AUTHORIZED
        rule.onNodeWithTag(TestTags.MAP_AR).performClick()
        rule.waitForTag(TestTags.AR_GUIDANCE)
        rule.onNodeWithTag(TestTags.AR_GUIDANCE).assertTextContains("xhere-512103", substring = true)
        rule.onNodeWithTag(TestTags.AR_PRIVACY).assertDoesNotExist()
    }

    @Test
    fun labelsRespectRevealAndDropHereCountsAsADeviceFix() {
        TestGraph.ar.nextSupport = ArSupport.READY
        TestGraph.ar.scriptedPose = ArPose(
            latitude = 42.093017,
            longitude = -79.23920,
            altitudeM = 200.0,
            headingDegrees = 180.0,
            horizontalAccuracyM = 8.0,
        )
        TestGraph.ar.scriptedAnchors = listOf(
            anchor("fenton", 42.09122, -79.23920, 48f, 180f),
            anchor("records", 42.0905, -79.24202, 48f, 340f),
            anchor("lunch", 42.09636, -79.24202, 200f, 180f),
        )
        TestGraph.location.setPermission(true)
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        rule.openMap()
        rule.waitUntil(timeoutMillis = 5_000) { TestGraph.location.collectionCount >= 1 }
        TestGraph.location.emit(LatLng(42.093017, -79.23920))
        rule.waitForTag(TestTags.MAP_AR)
        rule.onNodeWithTag(TestTags.MAP_AR).performClick()
        rule.waitForTag(TestTags.AR_PRIVACY)
        rule.onNodeWithText(
            "This application runs on Google Play Services for AR (ARCore), which is provided by Google and governed by the Google Privacy Policy.",
        ).assertIsDisplayed()
        rule.onNodeWithText(
            "To power this session, Google will process sensor data (e.g., camera and location). Learn more",
        ).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.AR_CAMERA).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.AR_CONTINUE).performClick()
        rule.waitForTag(TestTags.AR_CAMERA)
        rule.waitForTag(TestTags.arLabel("fenton"))
        rule.onNodeWithTag(TestTags.arLabel("fenton")).assertTextContains("Fenton Mansion")
        rule.onNodeWithText("Reuben", substring = true).assertDoesNotExist()
        rule.waitForTag(TestTags.arLabel("records"))
        rule.onNodeWithText("Public library building", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.arLabel("fenton")).performClick()
        rule.waitForTag(TestTags.DETAIL_TITLE)
        rule.onNodeWithTag(TestTags.DETAIL_TITLE).assertTextContains("Fenton", substring = true)
        rule.onNodeWithTag(TestTags.DETAIL_BACK).performClick()
        rule.waitForTag(TestTags.AR_DROP)
        rule.onNodeWithTag(TestTags.AR_DROP).performClick()
        rule.waitForTag(TestTags.DROP_PINNED)
        rule.onNodeWithTag(TestTags.DROP_PINNED).assertTextContains("±8 m", substring = true)
        rule.onNodeWithTag(TestTags.DROP_PINNED).assertTextContains("42.09302", substring = true)
        rule.onNodeWithTag(TestTags.DROP_TEXT).performTextInput("a general note about the benches")
        rule.onNodeWithTag(TestTags.DROP_TEXT_CONTINUE).performClick()
        rule.waitForTag(TestTags.suggestion("note"))
        rule.onNodeWithTag(TestTags.suggestion("note")).performScrollTo().performClick()
        rule.waitForTag(TestTags.DROP_SUCCESS)
        val posted = TestGraph.services.store.drops.values.first { it.title == "a general note about the benches" }
        assertTrue(posted.countsForCredit)
        assertEquals("standard", posted.locationTrust)
    }

    private fun anchor(shortId: String, latitude: Double, longitude: Double, x: Float, y: Float) = ResolvedAnchor(
        shortId = shortId,
        latitude = latitude,
        longitude = longitude,
        altitudeM = 200.0,
        kind = AnchorKind.TERRAIN,
        screenX = x,
        screenY = y,
    )
}

private fun AndroidComposeTestRule<*, MainActivity>.openMap() {
    waitForTag(TestTags.AUTH_EMAIL)
    onNodeWithTag(TestTags.AUTH_EMAIL).performTextInput("ada@xhere.world")
    onNodeWithTag(TestTags.AUTH_SEND_CODE).performClick()
    waitForTag(TestTags.AUTH_CODE)
    onNodeWithTag(TestTags.AUTH_CODE).performTextInput("123456")
    onNodeWithTag(TestTags.AUTH_VERIFY).performClick()
    waitForTag(TestTags.ONBOARDING_SKIP)
    onNodeWithTag(TestTags.ONBOARDING_SKIP).performClick()
    waitForTag(TestTags.SCREEN_MAP)
}

private fun AndroidComposeTestRule<*, MainActivity>.waitForTag(tag: String) {
    waitUntil(timeoutMillis = 8_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}
