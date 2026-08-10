package com.yourapp.audiobook.source.extra

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"

class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host] ?: emptyList()
}

fun buildClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .cookieJar(MemoryCookieJar())
    .addInterceptor { chain ->
        val original = chain.request()
        val request = original.newBuilder()
            .header("User-Agent", USER_AGENT)
            .build()
        chain.proceed(request)
    }
    .build()

suspend fun getHtml(client: OkHttpClient, url: String, referer: String? = null): String =
    withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val builder = Request.Builder().url(url)
                if (referer != null) builder.header("Referer", referer)
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} для $url")
                    return@withContext response.body?.string() ?: throw IOException("Пустой ответ для $url")
                }
            } catch (e: Exception) {
                if (attempt == MAX_ATTEMPTS - 1) throw e
                lastError = e
                delay(700L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Ошибка запроса для $url")
    }

suspend fun postForm(
    client: OkHttpClient,
    url: String,
    data: Map<String, String>,
    referer: String?,
    extraHeaders: Map<String, String> = emptyMap(),
): String = withContext(Dispatchers.IO) {
    var lastError: Exception? = null
    repeat(MAX_ATTEMPTS) { attempt ->
        try {
            val body = data.entries.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val builder = Request.Builder().url(url).post(body)
            if (referer != null) builder.header("Referer", referer)
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} для $url")
                return@withContext response.body?.string() ?: throw IOException("Пустой ответ для $url")
            }
        } catch (e: Exception) {
            if (attempt == MAX_ATTEMPTS - 1) throw e
            lastError = e
            delay(700L * (attempt + 1))
        }
    }
    throw lastError ?: IOException("Ошибка запроса для $url")
}

fun String.unescapeJs(): String =
    replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")

fun String?.toNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

fun String.absUrl(base: String): String =
    if (startsWith("http")) this else if (startsWith("/")) base.trimEnd('/') + this else base.trimEnd('/') + "/" + this

suspend fun estimateCount(
    client: OkHttpClient,
    url: String,
    cardSelector: String,
    referer: String? = null,
): Long? {
    val html = getHtml(client, url, referer)
    val doc = Jsoup.parse(html, url)
    val perPage = doc.select(cardSelector).size
    if (perPage == 0) return null
    val pageRegex = Regex("""(?:/page/|(?:\?|&)page=)(\d+)""")
    val maxPage = doc.select("a[href]").mapNotNull { link ->
        pageRegex.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
    }.maxOrNull()
    return if (maxPage != null) perPage.toLong() * maxPage else perPage.toLong()
}

private const val MAX_ATTEMPTS = 3
