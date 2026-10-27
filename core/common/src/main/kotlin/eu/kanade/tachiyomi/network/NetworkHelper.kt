package eu.kanade.tachiyomi.network

import android.content.Context
import com.google.net.cronet.okhttptransport.CronetInterceptor
import com.google.net.cronet.okhttptransport.RedirectStrategy
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.CronetCookieInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import kotlinx.coroutines.CoroutineScope
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import tachiyomi.core.common.util.system.logcat
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Inject
@SingleIn(AppScope::class)
class NetworkHelper(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val preferences: NetworkPreferences,
) {

    val cookieJar = AndroidCookieJar()

    private fun clientBuilder(): OkHttpClient.Builder = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30.seconds)
            .readTimeout(30.seconds)
            .callTimeout(2.minutes)
            .cache(
                Cache(
                    directory = File(context.cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                ),
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

        if (preferences.verboseLogging.get()) {
            val httpLoggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                if (preferences.redactSensitiveHeaders.get()) {
                    redactHeader("Authorization")
                    redactHeader("Auth")
                }
            }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
        }

        when (preferences.dohProvider.get()) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            PREF_DOH_QUAD101 -> builder.dohQuad101()
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            PREF_DOH_SHECAN -> builder.dohShecan()
            else -> builder
        }
    }

    /**
     * Chromium's network stack (embedded Cronet), or null when the preference is off or the engine
     * could not be created.
     */
    val cronetEngine: CronetEngine? by lazy {
        if (!preferences.cronet.get()) return@lazy null
        try {
            val providers = CronetProvider.getAllProviders(context).filter { it.isEnabled }
            val provider = providers.firstOrNull { it.name == CronetProvider.PROVIDER_NAME_APP_PACKAGED }
                ?: providers.firstOrNull { it.name != CronetProvider.PROVIDER_NAME_FALLBACK }
                ?: error("no native Cronet provider: ${providers.map { it.name }}")
            provider.createBuilder()
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, 5L * 1024 * 1024)
                .setUserAgent(defaultUserAgentProvider())
                .build()
                .also {
                    logcat(LogPriority.INFO) {
                        "Cronet: ${provider.name} ${provider.version}, engine ${it.versionString}"
                    }
                }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Cronet engine failed, using plain OkHttp" }
            null
        }
    }

    val client: OkHttpClient by lazy {
        val builder = clientBuilder()
            .addInterceptor(
                CloudflareInterceptor(context, cookieJar, scope, ::defaultUserAgentProvider),
            )
        cronetEngine?.let { engine ->
            // Cronet answers the request here, so OkHttp's cookie jar, redirects, cache, DoH and
            // network interceptors never run. Cookies and redirects are bridged back in, and with
            // verbose logging on, requests are logged here instead (headers as the app set them).
            builder.addInterceptor(CronetCookieInterceptor(cookieJar) { client.followRedirects })
            if (preferences.verboseLogging.get()) {
                builder.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS })
            }
            builder.addInterceptor(
                CronetInterceptor.newBuilder(engine)
                    .setRedirectStrategy(RedirectStrategy.withoutRedirects())
                    .build(),
            )
        }
        builder.build()
    }

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient get() = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent.get().trim()
}
