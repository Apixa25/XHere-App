package world.xhere.app.crash

import android.app.Application
import io.sentry.android.core.SentryAndroid
import world.xhere.app.BuildConfig

/**
 * Sentry, off unless a DSN was baked in at build time (PLAN §8, M9).
 * No account is created here. The free developer plan covers a closed test.
 * A blank value or the `.env.example` placeholder (`your-…`) does not start the SDK,
 * and the manifest sets `io.sentry.auto-init` to false so the provider does not either.
 */
object CrashReporting {
    fun start(app: Application) {
        val dsn = BuildConfig.SENTRY_DSN.trim()
        if (!isUsableDsn(dsn)) return
        SentryAndroid.init(app) { options ->
            options.dsn = dsn
            options.isSendDefaultPii = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
        }
    }

    /** A usable DSN is an http(s) URL that is not the placeholder. */
    fun isUsableDsn(dsn: String): Boolean {
        val value = dsn.trim()
        if (value.isEmpty() || value.startsWith("your-")) return false
        return value.startsWith("https://") || value.startsWith("http://")
    }
}
