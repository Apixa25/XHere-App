package world.xhere.app.ui

/** Stable ids for Compose UI tests. User-facing copy lives in strings.xml. */
internal object TestTags {
    const val AUTH_EMAIL = "auth_email"
    const val AUTH_SEND_CODE = "auth_send_code"
    const val AUTH_CODE = "auth_code"
    const val AUTH_VERIFY = "auth_verify"
    const val AUTH_GOOGLE = "auth_google"
    const val AUTH_ERROR = "auth_error"
    const val AUTH_OFFLINE_NOTICE = "auth_offline_notice"
    const val AUTH_SWITCH_EMAIL = "auth_switch_email"
    const val SIGNED_OUT_NOTICE = "signed_out_notice"

    const val ONBOARDING_ALLOW = "onboarding_allow"
    const val ONBOARDING_SKIP = "onboarding_skip"

    const val NAV_MAP = "nav_map"
    const val NAV_DROP = "nav_drop"
    const val NAV_PROFILE = "nav_profile"
    const val SCREEN_MAP = "screen_map"
    const val SCREEN_DROP = "screen_drop"
    const val SCREEN_PROFILE = "screen_profile"
    const val OPEN_SETTINGS = "open_settings"

    const val SETTINGS_SIGN_OUT = "settings_sign_out"
    const val SETTINGS_DELETE = "settings_delete"
    const val SETTINGS_DELETE_CONFIRM = "settings_delete_confirm"
    const val SETTINGS_DELETE_KEEP = "settings_delete_keep"

    const val CHIP_BAR = "chip_bar"
    const val CHIP_ALL = "chip_all"
    const val SAVE_PRESET = "save_preset"
    const val PRESET_NAME = "preset_name"
    const val PRESET_SAVE = "preset_save"
    const val TEASER_CARD = "teaser_card"
    const val TEASER_TITLE = "teaser_title"
    const val TEASER_CATEGORY = "teaser_category"
    const val TEASER_BODY = "teaser_body"
    const val TEASER_LOCKED = "teaser_locked"
    const val TEASER_OPEN = "teaser_open"
    const val TEASER_CLOSE = "teaser_close"
    const val NEAR_ME = "near_me"
    const val NEAR_ME_LIST = "near_me_list"
    const val MY_LOCATION = "my_location"
    const val MAP_ATTRIBUTION = "map_attribution"
    const val MAP_NOTICE = "map_notice"
    const val MAP_MARKERS = "map_markers"
    const val MARKER_CLUSTER = "marker_cluster"
    const val SUBCATEGORY_SHEET = "subcategory_sheet"
    const val SUBCATEGORY_APPLY = "subcategory_apply"
    const val MAP_DROP_FAB = "map_drop_fab"
    const val MAP_PAN_AWAY = "map_pan_away"
    const val MAP_ZOOM_OUT = "map_zoom_out"
    const val PREVIEW_JAMESTOWN = "preview_jamestown"

    fun group(slug: String): String = "chip_group_$slug"

    fun preset(name: String): String = "chip_preset_${name.lowercase()}"

    fun marker(shortId: String): String = "marker_$shortId"

    fun nearRow(shortId: String): String = "near_$shortId"

    fun subcategory(slug: String): String = "sub_$slug"

    const val DROP_SHUTTER = "drop_shutter"
    const val DROP_PICK = "drop_pick"
    const val DROP_TEXT = "drop_text"
    const val DROP_TEXT_CONTINUE = "drop_text_continue"
    const val DROP_MORE = "drop_more"
    const val DROP_POST = "drop_post"
    const val DROP_GUIDELINES = "drop_guidelines"
    const val DROP_NOTICE = "drop_notice"
    const val DROP_ERROR = "drop_error"
    const val DROP_SUCCESS = "drop_success"
    const val DROP_DUPLICATE = "drop_duplicate"
    const val DROP_FUZZ = "drop_fuzz"
    const val DROP_ANOTHER = "drop_another"

    fun suggestion(slug: String): String = "suggestion_$slug"

    fun category(slug: String): String = "category_$slug"

    fun field(name: String): String = "field_$name"

    const val SCREEN_DETAIL = "screen_detail"
    const val DETAIL_BACK = "detail_back"
    const val DETAIL_TITLE = "detail_title"
    const val DETAIL_CATEGORY = "detail_category"
    const val DETAIL_BODY = "detail_body"
    const val DETAIL_LOCKED = "detail_locked"
    const val DETAIL_DISTANCE = "detail_distance"
    const val DETAIL_BADGE = "detail_badge"
    const val DETAIL_PROGRESS = "detail_progress"
    const val DETAIL_FRESHNESS = "detail_freshness"
    const val DETAIL_STILL_TRUE = "detail_still_true"
    const val DETAIL_NOT_TRUE = "detail_not_true"
    const val DETAIL_SHARE = "detail_share"
    const val DETAIL_NOTICE = "detail_notice"
    const val DETAIL_LOCATION = "detail_location"

    const val DETAIL_REPORT = "detail_report"
    const val DETAIL_HIDE = "detail_hide"
    const val DETAIL_REPORT_AUTHOR = "detail_report_author"
    const val DETAIL_BLOCK_AUTHOR = "detail_block_author"
    const val DETAIL_SAFETY_MESSAGE = "detail_safety_message"
    const val DETAIL_UNDO_HIDE = "detail_undo_hide"
    const val REPORT_FORM = "report_form"
    const val REPORT_DETAILS = "report_details"
    const val REPORT_ALSO_HIDE = "report_also_hide"
    const val REPORT_SEND = "report_send"
    const val BLOCK_CONFIRM = "block_confirm"
    const val SAFETY_FOOTER = "safety_footer"
    const val SAFETY_TIP_CALL = "safety_tip_call"

    fun reportReason(slug: String): String = "report_reason_$slug"

    const val DROP_SAFETY_PANEL = "drop_safety_panel"
    const val DROP_SAFETY_FILTER = "drop_safety_filter"
    const val DROP_SAFETY_HELD = "drop_safety_held"
    const val DROP_SAFETY_UNAVAILABLE = "drop_safety_unavailable"

    const val SETTINGS_GUIDELINES = "settings_guidelines"
    const val SETTINGS_BLOCKED = "settings_blocked"
    const val SETTINGS_EXPORT = "settings_export"
    const val SETTINGS_EXPORT_STATUS = "settings_export_status"
    const val SETTINGS_MODERATION = "settings_moderation"

    const val SCREEN_GUIDELINES = "screen_guidelines"
    const val SCREEN_LISTS = "screen_lists"
    const val LISTS_BLOCKED_EMPTY = "lists_blocked_empty"
    const val LISTS_HIDDEN_EMPTY = "lists_hidden_empty"

    fun unblock(handle: String): String = "unblock_$handle"

    fun unhide(shortId: String): String = "unhide_$shortId"

    const val SCREEN_MODERATION = "screen_moderation"
    const val MOD_EMPTY = "mod_empty"
    const val MOD_DENIED = "mod_denied"
    const val MOD_MESSAGE = "mod_message"

    fun queueItem(id: String): String = "queue_$id"

    fun modAction(action: String, id: String): String = "mod_${action}_$id"
}
