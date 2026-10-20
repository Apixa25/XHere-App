package world.xhere.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import world.xhere.app.crash.CrashReporting
import world.xhere.app.di.appModule
import world.xhere.shared.data.supabase.prepareSupabaseSessionStore
import world.xhere.shared.places.PublicSources

class XHereApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .addInterceptor { chain ->
                                    chain.proceed(
                                        chain.request().newBuilder()
                                            .header("User-Agent", PublicSources.USER_AGENT)
                                            .build(),
                                    )
                                }
                                .build()
                        },
                    ),
                )
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        // No-op unless SENTRY_DSN was set for this build. See CrashReporting.
        CrashReporting.start(this)
        // The manifest content providers do this on a device. Robolectric often
        // skips them, and the Supabase client needs the context before Koin builds it.
        prepareSupabaseSessionStore(this)
        startXHereKoin(this, listOf(appModule))
    }
}

internal fun startXHereKoin(application: Application, modules: List<org.koin.core.module.Module>) {
    if (GlobalContext.getOrNull() != null) stopKoin()
    startKoin {
        androidContext(application)
        modules(modules)
    }
}
