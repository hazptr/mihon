package eu.kanade.tachiyomi.network

import okhttp3.CookieJar
import okhttp3.Interceptor
import uy.kohesive.injekt.injectLazy

/**
 * The app's own transport for extension requests: Cronet, when the user turned it on.
 */
@Suppress("Unused")
object AppTransport {

    private val network: NetworkHelper by injectLazy()

    fun interceptor(cookieJar: CookieJar, followRedirects: Boolean): Interceptor? =
        network.cronetInterceptor(cookieJar, followRedirects)
}
