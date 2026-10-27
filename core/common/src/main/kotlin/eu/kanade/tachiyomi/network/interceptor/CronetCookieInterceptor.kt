package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.chromium.net.NetworkException
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector

/**
 * The cookie and redirect half of OkHttp's BridgeInterceptor and
 * RetryAndFollowUpInterceptor, for requests that Cronet answers before OkHttp's own core runs.
 * [transport] is the CronetInterceptor, built with redirects off.
 *
 * Chromium gives an HTTP proxy 10 seconds to answer CONNECT on Android, where OkHttp waits for its read
 * timeout. A request whose tunnel times out was never sent, so it goes through OkHttp's own connection instead.
 */
class CronetCookieInterceptor(
    private val cookieJar: CookieJar,
    private val followRedirects: Boolean,
    private val transport: Interceptor,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var prior: Response? = null
        repeat(MAX_REDIRECTS + 1) {
            val hop = withCookies(request)
            val response = try {
                transport.intercept(object : Interceptor.Chain by chain {
                    override fun request() = hop
                })
            } catch (e: IOException) {
                if (!isProxyTimeout(hop, e)) throw e
                chain.proceed(hop)
            }
                .newBuilder()
                .priorResponse(prior)
                .build()
            cookieJar.saveFromResponse(request.url, Cookie.parseAll(request.url, response.headers))
            val next = if (followRedirects) followUp(request, response) else null
            if (next == null) return response
            prior = response.newBuilder().body(ResponseBody.EMPTY).build()
            response.close()
            request = next
        }
        throw IOException("Too many redirects: $MAX_REDIRECTS")
    }

    /** Chromium's ERR_TIMED_OUT, which it gives while connecting, with a proxy set for [request]. */
    private fun isProxyTimeout(request: Request, e: IOException): Boolean {
        val cause = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<NetworkException>().firstOrNull()
        if (cause?.cronetInternalErrorCode != ERR_TIMED_OUT) return false
        val proxies = ProxySelector.getDefault()?.select(request.url.toUri()).orEmpty()
        return proxies.any { it.type() != Proxy.Type.DIRECT }
    }

    /** Like BridgeInterceptor: jar cookies, when there are any, replace a Cookie header set by the caller. */
    private fun withCookies(request: Request): Request {
        val cookies = cookieJar.loadForRequest(request.url)
        if (cookies.isEmpty()) return request
        return request.newBuilder()
            .header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
            .build()
    }

    /** OkHttp's redirect rules (RetryAndFollowUpInterceptor.buildRedirectRequest), minus proxy auth. */
    private fun followUp(request: Request, response: Response): Request? {
        if (response.code !in REDIRECT_CODES) return null
        val location = response.header("Location") ?: return null
        val url = request.url.resolve(location) ?: return null
        val builder = request.newBuilder().url(url)
        val method = request.method
        if (method != "GET" && method != "HEAD") {
            val keepBody = response.code == 307 || response.code == 308
            if (keepBody) {
                builder.method(method, request.body)
            } else {
                builder.method("GET", null)
                builder.removeHeader("Transfer-Encoding")
                builder.removeHeader("Content-Length")
                builder.removeHeader("Content-Type")
            }
        }
        if (url.host != request.url.host || url.port != request.url.port || url.scheme != request.url.scheme) {
            builder.removeHeader("Authorization")
        }
        return builder.build()
    }

    private companion object {
        const val MAX_REDIRECTS = 20
        const val ERR_TIMED_OUT = -7
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}
