package world.xhere.app

import android.Manifest
import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import world.xhere.app.ui.TestTags

/**
 * Every map sheet can leave the way a person expects: the system back
 * button, and tapping Map again. Near me also names the category.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class SheetDismissTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun nearMeNamesTheCategoryAndClosesFromBackTheScrimAndTheMapTab() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.NEAR_ME).performClick()
        rule.waitForTag(TestTags.NEAR_ME_LIST)
        rule.onNodeWithTag(TestTags.nearRow("lunch")).performScrollTo()
            .assertTextContains("Eatery tip", substring = true)
            .assertTextContains("🍽️", substring = true)
        rule.onNodeWithTag(TestTags.nearRow("fenton")).performScrollTo()
            .assertTextContains("Historical fact", substring = true)
            .assertTextContains("🏛️", substring = true)
        rule.onNodeWithTag(TestTags.SHEET_CLOSE).performClick()
        rule.assertGone(TestTags.NEAR_ME_LIST)

        rule.onNodeWithTag(TestTags.NEAR_ME).performClick()
        rule.waitForTag(TestTags.NEAR_ME_LIST)
        rule.pressBack()
        rule.assertGone(TestTags.NEAR_ME_LIST)

        rule.onNodeWithTag(TestTags.NEAR_ME).performClick()
        rule.waitForTag(TestTags.NEAR_ME_LIST)
        rule.onNodeWithTag(TestTags.SHEET_SCRIM).performSemanticsAction(SemanticsActions.OnClick)
        rule.assertGone(TestTags.NEAR_ME_LIST)

        rule.waitForTag(TestTags.NEAR_ME)
        rule.onNodeWithTag(TestTags.NEAR_ME).performClick()
        rule.waitForTag(TestTags.NEAR_ME_LIST)
        rule.onNodeWithTag(TestTags.NAV_MAP).performClick()
        rule.assertGone(TestTags.NEAR_ME_LIST)

        rule.onNodeWithTag(TestTags.NEAR_ME).performClick()
        rule.waitForTag(TestTags.nearRow("lunch"))
        rule.onNodeWithTag(TestTags.NAV_EXPLORE).performClick()
        rule.waitForTag(TestTags.SCREEN_EXPLORE)
        rule.onNodeWithTag(TestTags.NAV_MAP).performClick()
        rule.waitForTag(TestTags.SCREEN_MAP)
        rule.assertGone(TestTags.NEAR_ME_LIST)
    }

    @Test
    fun timeMachineClosesFromBackAndTheMapTab() {
        rule.openMap()
        rule.openAndDismiss(TestTags.MAP_TIME, TestTags.TIME_SHEET)
    }

    @Test
    fun hereCardClosesFromBackAndTheMapTab() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.MAP_LONG_PRESS).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForTag(TestTags.HERE_SHEET)
        rule.pressBack()
        rule.assertGone(TestTags.HERE_SHEET)
        rule.onNodeWithTag(TestTags.MAP_LONG_PRESS).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForTag(TestTags.HERE_SHEET)
        rule.onNodeWithTag(TestTags.NAV_MAP).performClick()
        rule.assertGone(TestTags.HERE_SHEET)
    }

    @Test
    fun subcategorySheetClosesFromBackAndTheMapTab() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.group("history_stories")).performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForTag(TestTags.SUBCATEGORY_SHEET)
        rule.pressBack()
        rule.assertGone(TestTags.SUBCATEGORY_SHEET)
        rule.onNodeWithTag(TestTags.group("history_stories")).performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForTag(TestTags.SUBCATEGORY_SHEET)
        rule.onNodeWithTag(TestTags.NAV_MAP).performClick()
        rule.assertGone(TestTags.SUBCATEGORY_SHEET)
    }

    @Test
    fun thingsAroundClosesFromBackAndTheMapTab() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        rule.openMap()
        rule.openAndDismiss(TestTags.THINGS_AROUND, TestTags.THINGS_SHEET)
    }

    @Test
    fun savePresetClosesFromBackAndTheMapTab() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.group("history_stories")).performScrollTo().performClick()
        rule.waitForTag(TestTags.SAVE_PRESET)
        rule.onNodeWithTag(TestTags.SAVE_PRESET).performScrollTo().performClick()
        rule.waitForTag(TestTags.PRESET_NAME)
        rule.pressBack()
        rule.assertGone(TestTags.PRESET_NAME)
        rule.onNodeWithTag(TestTags.SAVE_PRESET).performScrollTo().performClick()
        rule.waitForTag(TestTags.PRESET_NAME)
        rule.onNodeWithTag(TestTags.NAV_MAP).performClick()
        rule.assertGone(TestTags.PRESET_NAME)
    }

    @Test
    fun levelUpClosesFromBackAndTappingProfileAgain() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.waitForTag(TestTags.TIER_CHIP)
        rule.onNodeWithTag(TestTags.TIER_CHIP).performScrollTo().performClick()
        rule.waitForTag(TestTags.LEVEL_UP)
        rule.onNodeWithTag(TestTags.LEVEL_UP_CLOSE).performSemanticsAction(SemanticsActions.OnClick)
        rule.assertGone(TestTags.LEVEL_UP)
        rule.waitForTag(TestTags.TIER_CHIP)
        rule.onNodeWithTag(TestTags.TIER_CHIP).performScrollTo().performClick()
        rule.waitForTag(TestTags.LEVEL_UP)
        rule.pressBack()
        rule.assertGone(TestTags.LEVEL_UP)
        rule.waitForTag(TestTags.TIER_CHIP)
        rule.onNodeWithTag(TestTags.TIER_CHIP).performScrollTo().performClick()
        rule.waitForTag(TestTags.LEVEL_UP)
        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.assertGone(TestTags.LEVEL_UP)
        rule.onNodeWithTag(TestTags.SCREEN_PROFILE).assertIsDisplayed()
    }
}

private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.openAndDismiss(openTag: String, sheetTag: String) {
    onNodeWithTag(openTag).performClick()
    waitForTag(sheetTag)
    pressBack()
    assertGone(sheetTag)
    onNodeWithTag(openTag).performClick()
    waitForTag(sheetTag)
    onNodeWithTag(TestTags.NAV_MAP).performClick()
    assertGone(sheetTag)
}

private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.openMap() {
    waitForTag(TestTags.AUTH_EMAIL)
    onNodeWithTag(TestTags.AUTH_EMAIL).performTextInput("ada@xhere.world")
    onNodeWithTag(TestTags.AUTH_SEND_CODE).performClick()
    waitForTag(TestTags.AUTH_CODE)
    onNodeWithTag(TestTags.AUTH_CODE).performTextInput("123456")
    onNodeWithTag(TestTags.AUTH_VERIFY).performClick()
    waitForTag(TestTags.ONBOARDING_SKIP)
    onNodeWithTag(TestTags.ONBOARDING_SKIP).performClick()
    waitForTag(TestTags.SCREEN_MAP)
    waitForTag(TestTags.CHIP_BAR)
}

private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForTag(tag: String) {
    waitUntil(timeoutMillis = 8_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}

private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.assertGone(tag: String) {
    waitUntil(timeoutMillis = 8_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }
}

private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.pressBack() {
    activityRule.scenario.onActivity { activity ->
        activity.onBackPressedDispatcher.onBackPressed()
    }
    waitForIdle()
}
