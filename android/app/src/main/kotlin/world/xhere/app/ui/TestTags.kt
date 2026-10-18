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
    const val MAP_PAN_AWAY = "map_pan_away"
    const val MAP_ZOOM_OUT = "map_zoom_out"
    const val PREVIEW_JAMESTOWN = "preview_jamestown"

    fun group(slug: String): String = "chip_group_$slug"

    fun preset(name: String): String = "chip_preset_${name.lowercase()}"

    fun marker(shortId: String): String = "marker_$shortId"

    fun nearRow(shortId: String): String = "near_$shortId"

    fun subcategory(slug: String): String = "sub_$slug"
}
