package world.xhere.app.voice

import android.Manifest
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import world.xhere.app.FreshSessionRule
import world.xhere.app.MainActivity
import world.xhere.app.TestGraph
import world.xhere.app.TestXHereApplication
import world.xhere.app.ui.TestTags
import world.xhere.shared.access.AccessStatus
import world.xhere.shared.demo.JamestownDemo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class VoiceAndAccessTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun spokenPotholePostsAtTheCurrentFix() {
        rule.openMap()
        grantMic()
        TestGraph.location.setPermission(true)
        TestGraph.location.emit(JamestownDemo.center)
        TestGraph.speech.next = "pothole here"
        rule.onNodeWithTag(TestTags.MAP_MORE).performClick()
        rule.onNodeWithTag(TestTags.VOICE_MIC).performClick()
        rule.waitForTag(TestTags.VOICE_POST)
        rule.onNodeWithTag(TestTags.VOICE_DROP_TITLE).assertTextContains("Pothole")
        TestGraph.location.emit(JamestownDemo.center)
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.VOICE_POST).performClick()
        rule.waitForTag(TestTags.VOICE_POSTED)
        val posted = TestGraph.services.store.drops.values.filter {
            it.authorId == TestGraph.profile.id && it.category.slug == "hazard"
        }
        assertEquals(1, posted.size)
        assertEquals("Pothole", posted.single().title)
        assertEquals(JamestownDemo.center.latitude, posted.single().trueLocation.latitude, 0.0001)
    }

    @Test
    fun aroundMeIsReadAloudAndDoesNotPost() {
        rule.openMap()
        grantMic()
        TestGraph.location.setPermission(true)
        TestGraph.location.emit(JamestownDemo.center)
        TestGraph.speech.next = "what's around me?"
        val before = TestGraph.services.store.drops.size
        rule.onNodeWithTag(TestTags.MAP_MORE).performClick()
        rule.onNodeWithTag(TestTags.VOICE_MIC).performClick()
        rule.waitForTag(TestTags.VOICE_SPOKEN)
        assertTrue(TestGraph.speaker.last.orEmpty().contains("around"))
        assertEquals(before, TestGraph.services.store.drops.size)
    }

    @Test
    fun deniedMicrophoneOffersSettings() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.MAP_MORE).performClick()
        rule.onNodeWithTag(TestTags.VOICE_MIC).performClick()
        rule.waitForTag(TestTags.VOICE_DENIED)
        rule.onNodeWithTag(TestTags.VOICE_SETTINGS).assertTextContains("Settings", substring = true)
    }

    @Test
    fun threeTapsSaveAnAccessiblePlace() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.MAP_TAP_SPOT).performClick()
        rule.waitForTag(TestTags.ACCESS_CHECK)
        rule.onNodeWithTag(TestTags.ACCESS_CHECK).performScrollTo().performClick()
        rule.waitForTag(TestTags.ACCESS_YES)
        rule.onNodeWithTag(TestTags.ACCESS_YES).performClick()
        rule.onNodeWithTag(TestTags.ACCESS_YES).performClick()
        rule.onNodeWithTag(TestTags.ACCESS_YES).performClick()
        rule.waitForTag(TestTags.ACCESS_SAVE)
        rule.onNodeWithTag(TestTags.ACCESS_SAVE).performClick()
        rule.waitForTag(TestTags.ACCESS_STATUS)
        rule.onNodeWithTag(TestTags.ACCESS_STATUS).assertTextContains("Accessible", substring = true)
        val summary = TestGraph.services.store.accessChecks.single()
        assertEquals(world.xhere.shared.access.AccessAnswer.YES, summary.stepFree)
        assertTrue(summary.placeKey.startsWith("cell:"))
    }

    @Test
    fun accessibilityLayerListsTheWheelchairPin() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.MAP_TAP_SPOT).performClick()
        rule.waitForTag(TestTags.ACCESS_CHECK)
        rule.onNodeWithTag(TestTags.ACCESS_CHECK).performScrollTo().performClick()
        rule.onNodeWithTag(TestTags.ACCESS_YES).performClick()
        rule.onNodeWithTag(TestTags.ACCESS_NO).performClick()
        rule.onNodeWithTag(TestTags.ACCESS_UNKNOWN).performClick()
        rule.onNodeWithTag(TestTags.ACCESS_SAVE).performClick()
        rule.waitForTag(TestTags.ACCESS_STATUS)
        rule.onNodeWithTag(TestTags.HERE_CLOSE).performClick()
        rule.onNodeWithTag(TestTags.MAP_MORE).performClick()
        rule.onNodeWithTag(TestTags.MAP_ACCESS).performClick()
        val key = TestGraph.services.store.accessChecks.single().placeKey
        rule.waitForTag(TestTags.accessPin(key))
        val pin = TestGraph.services.access
        assertEquals(AccessStatus.PARTIAL, kotlinx.coroutines.runBlocking {
            pin.summary(key)!!.status
        })
    }

    private fun grantMic() {
        val app = rule.activity.application
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(rule.activity).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val result = androidx.core.content.ContextCompat.checkSelfPermission(
            rule.activity,
            Manifest.permission.RECORD_AUDIO,
        )
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, result)
    }
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
