package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException

/**
 * The cookie and redirect half of OkHttp's BridgeInterceptor and
 * RetryAndFollowUpInterceptor, for requests that Cronet answers before OkHttp's own core runs.
 * [transport] is the CronetInterceptor, built with redirects off.
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
            val response = transport.intercept(object : Interceptor.Chain by chain {
                override fun request() = hop
            })
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
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}
