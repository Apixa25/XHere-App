# XHere store compliance

Where each store rule for user-generated content (UGC), crime reporting, and account deletion is implemented, and which test proves it. Built in M7 (PLAN §7 M7, safety rules in PLAN §4.3). Re-check this page against the live policies before each store submission. Policies change, and this is engineering's reading of them, not legal advice.

Paths are from the repo root. "pgTAP" means `supabase/tests/database/`. "App test" means `android/app/src/test/kotlin/world/xhere/app/`.

## Google Play: User Generated Content policy

| Requirement | Implementation | Tests |
| --- | --- | --- |
| Users accept terms that define objectionable content before they create or upload UGC | Sign-in screen states that continuing means agreeing to the Terms and Community guidelines, with zero tolerance for objectionable content or abusive users, and links both (`AuthScreen.kt` `AgreementNotice`). The composer repeats it under Post (`drop_agreement`). Safety categories also need an explicit acknowledgement checkbox, enforced by `create_drop` (`guidelines_not_acknowledged`). Pages: `web/src/pages/terms.astro`, `web/src/pages/guidelines.astro` | App test `safety/SafetyFlowsTest.signInStatesTheTermsAndZeroToleranceBeforeAnyonePosts`; pgTAP `03_create_drop`, `04_crime_rules` (acknowledgement) |
| Objectionable content is defined, and it is consistent with Play's policies | Guidelines: "Not allowed anywhere", sales rules (no weapons, drugs, regulated items), Safety rules, and child safety. The in-app copy is `safety/GuidelinesScreen.kt` (Settings › Community guidelines), the same rules as the website | App test `SafetyFlowsTest.guidelinesMatchTheWebsiteAndShowTheTipLine` |
| Robust, effective, ongoing moderation | Text filters per category (`contact_info`, `person_descriptors` in `20261018000600_taxonomy.sql`; `street_address` in `20261018001000_safety_compliance.sql`), checked on the server and previewed in the composer (`SafetyFlow.filterHit`). Pre-moderation by category and tier (`moderation_mode`). Reports auto-hide a drop at the category's `reports_to_hide` (2 for Safety, usually 3). Moderator queue (`moderation_queue` RPC, `safety/ModerationScreen.kt`). Every moderator action is written to the append-only `public.moderation_actions` | pgTAP `09_safety_compliance` (pre-moderation of New users' Safety posts, auto-hide threshold, moderator-only access, audit rows, append-only log); shared `SafetyFakeTest` and `SafetyContractTest`; app tests `QuickDropTest.a_new_accounts_safety_alert_walks_the_safety_steps_and_is_held_for_review`, `describing_a_person_is_caught_before_posting_and_the_blur_is_explained`, `SafetyFlowsTest.aModeratorApprovesAHeldSafetyAlertAndEveryActionIsAudited`, `aModeratorRemovesAReportedDrop` |
| In-app reporting of objectionable UGC and users | Drop screen: Report drop (reason, details, optional hide) and Report author (profile report). Both call the `report` RPC (`20261018000400_rpcs.sql`). `safety/SafetyComponents.kt` `ReportForm` | App tests `SafetyFlowsTest.reportingADropWithAlsoHideFilesTheReportAndHidesItForMeOnly`, `reportingTheAuthorFilesAProfileReport`; pgTAP `09_safety_compliance` (profile reports) |
| In-app blocking of UGC and users | Block author (works both ways in every read RPC), Hide for me (one drop), and Settings › Blocked and hidden to undo either (`safety/SafetyListsScreen.kt`) | App tests `SafetyFlowsTest.blockingTheAuthorHidesDropsBothWaysAndUnblockUndoesIt`, `hideForMeShowsUpInBlockedAndHiddenAndShowAgainRestoresIt`; pgTAP `09_safety_compliance` (blocks both ways, hide-for-me), `02_nearby` |
| No monetization of objectionable UGC | Not applicable. XHere has no payments, ads, or creator payouts | n/a |
| Child Safety Standards (social apps): published standards against CSAE, an in-app way to report, handling of CSAM, compliance with child safety laws, and a child safety point of contact | `web/src/pages/guidelines.astro#child-safety` (zero tolerance, removal and suspension, NCMEC CyberTipline reports, report in app or by email, the owner as point of contact). In-app Guidelines has the same section. Reporting uses the same Report flow | App test `SafetyFlowsTest.guidelinesMatchTheWebsiteAndShowTheTipLine` (checks the CyberTipline line); `web` build |

## Google Play: account deletion and data

| Requirement | Implementation | Tests |
| --- | --- | --- |
| Delete the account from inside the app | Settings › Delete account, with a confirmation dialog, calls `delete_my_account` (`20261018000400_rpcs.sql`). The account is signed out at once and purged by pg_cron after 30 days. Moderation records are kept as the delete-account page describes | App test `AuthAndOnboardingTest.delete_account_calls_the_repository_and_returns_to_auth`; shared `InMemoryXHereTest.deleteAccountSignsOutAndAModeratorCanRemove`; pgTAP `06_moderation`, `02_nearby` |
| A web link to request deletion without the app (Data safety form) | `https://xhere.world/delete-account` (`web/src/pages/delete-account.astro`): steps in the app, an email request, what is deleted, and what is kept | `web` build and `verify` |
| Data safety answers match the app | `web/src/pages/privacy.astro` lists what is collected. The app also offers a copy of your data: Settings › Download my data calls `export_my_data` and hands a JSON file to the share sheet through a private `FileProvider` | pgTAP `09_safety_compliance` (export keys, only your own rows); shared `SafetyFakeTest` and `SafetyContractTest`; app tests `SafetyFlowsTest.downloadMyDataHandsTheExportToTheShareSheet`, `settings/DataExportProviderTest` |

## Apple App Review Guideline 1.2 (User-Generated Content)

| Requirement | Implementation | Tests |
| --- | --- | --- |
| A method for filtering objectionable material from being posted | Server-side `content_filters` on each category, plus the composer preview that disables Post (`DropScreen.kt` `SafetyPanelCard`). Pre-moderation holds Safety posts from New accounts | pgTAP `09_safety_compliance`, `04_crime_rules`; app test `QuickDropTest.describing_a_person_is_caught_before_posting_and_the_blur_is_explained` |
| A mechanism to report offensive content, and timely responses to concerns | Report drop and Report author (see above). Reports land in the moderator queue (Settings › Moderation queue, only for `moderator`/`admin` roles). Terms and in-app guidelines state the aim to review within 24 hours. Auto-hide covers the gap before a moderator looks | App tests `SafetyFlowsTest.theQueueIsNotOfferedToRegularAccounts`, `aModeratorApprovesAHeldSafetyAlertAndEveryActionIsAudited`; pgTAP `09_safety_compliance` (auto-hide, moderator-only) |
| The ability to block abusive users from the service | Block author. Blocked users' drops are hidden from you and yours from them | App test `SafetyFlowsTest.blockingTheAuthorHidesDropsBothWaysAndUnblockUndoesIt`; pgTAP `09_safety_compliance` |
| Published contact information so users can reach you easily | `hello@xhere.world` in the in-app guidelines and on `web/src/pages/support.astro`, `guidelines.astro`, and `delete-account.astro` | App test `SafetyFlowsTest.guidelinesMatchTheWebsiteAndShowTheTipLine` |

## Apple 1.7 (reporting criminal activity) and 5.1.1(v) (account deletion)

| Requirement | Implementation | Tests |
| --- | --- | --- |
| Apps for reporting alleged criminal activity must involve local law enforcement, and are only offered where that involvement is active | `incident_report` is offered only where a police tip line is configured. The app hides it elsewhere (`shared/.../safety/Safety.kt` `SafetyDirectory`, `SafetyFlow.availableAt`). The server refuses it too: `20261018001100_tip_lines.sql` (`private.tip_lines`, seeded with the Jamestown Police anonymous tip line over Chautauqua County) raises `incident_reports_unavailable`. Every Safety drop and the composer show the tip line and 911 (`SafetyFooter`). The disclaimer says XHere is not a way to contact the police | pgTAP `10_tip_lines`; shared `SafetyFakeTest.incidentReportsNeedATipLineWhereTheyArePosted` (in `SafetyRepositoryTest.kt`), `SafetyTest`; app test `SafetyFlowsTest.aSafetyDropShowsTheTipLineFooter` |
| Extra safety for crime posts | Regular tier or above, held for review unless Trusted, about 100 m location blur (only `incident_report`), 72-hour expiry, no people descriptors, contact details, or street addresses, 2 reports hide it | pgTAP `04_crime_rules`, `09_safety_compliance`; app tests `QuickDropTest.incident_report_explains_the_tier_and_the_911_notice`, `incident_tier_and_descriptors_use_plain_language` |
| Account deletion inside the app | Same as the Google Play row above | Same tests |

## Known gaps

- There is no in-app appeals screen. Appeals go to `hello@xhere.world`, as the guidelines say.
- The 24-hour review target only holds once someone has the `moderator` role on the live project.
- Tip lines live in `private.tip_lines` and in the shared `SafetyDirectory`. They move into the per-region config in M16b. A new region gets incident reports only after its tip line is added to both.
- iOS (M15) has to repeat the report, block, hide, guidelines, terms notice, deletion, and export screens before App Review.
