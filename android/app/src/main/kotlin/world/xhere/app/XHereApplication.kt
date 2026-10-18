package world.xhere.app

import android.app.Application
import android.util.Log
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import world.xhere.app.crash.CrashReporting
import world.xhere.app.di.appModule
import world.xhere.shared.data.supabase.prepareSupabaseSessionStore

class XHereApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // No-op unless SENTRY_DSN was set for this build. See CrashReporting.
        CrashReporting.start(this)
        // The manifest content providers do this on a device. Robolectric often
        // skips them, and the Supabase client needs the context before Koin builds it.
        prepareSupabaseSessionStore(this)
        startXHereKoin(this, listOf(appModule))
        // The android / release job greps for this after installing the minified APK.
        Log.i("XHere", "process started")
    }
}

internal fun startXHereKoin(application: Application, modules: List<org.koin.core.module.Module>) {
    if (GlobalContext.getOrNull() != null) stopKoin()
    startKoin {
        androidContext(application)
        modules(modules)
    }
}
