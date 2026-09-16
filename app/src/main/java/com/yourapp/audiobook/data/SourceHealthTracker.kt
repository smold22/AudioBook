package com.yourapp.audiobook.data

import android.util.Log
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.extra.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Отслеживает работоспособность источников: id источника -> отвечает ли он. */
class SourceHealthTracker {

    private val _status = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val status: StateFlow<Map<String, Boolean>> = _status.asStateFlow()

    private val _currentlyChecking = MutableStateFlow<String?>(null)
    val currentlyChecking: StateFlow<String?> = _currentlyChecking.asStateFlow()

    /** true, пока идёт серия проверок ([checkAllSequentially]). Используется для UI запуска. */
    private val _checking = MutableStateFlow(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    /** Проверяет все источники последовательно и сохраняет результаты в [status].
     *  Если проверка уже идёт, новый запуск игнорируется. */
    suspend fun checkAllSequentially(sources: List<AudiobookSource>) {
        if (_checking.value) return
        _checking.value = true
        try {
            sources.forEach { source ->
                _currentlyChecking.value = source.id
                _status.value = _status.value + (source.id to check(source))
            }
        } finally {
            _currentlyChecking.value = null
            _checking.value = false
        }
    }

    private suspend fun check(source: AudiobookSource): Boolean {
        val ok = ping(source.baseUrl)
        Log.w(TAG, "check ${source.id} (${source.baseUrl}) -> $ok")
        return ok
    }

    /** Любой HTTP-ответ (даже 403/500) значит, что сервер отвечает.
     *  Если ответа нет в течение [REQUEST_TIMEOUT_MS], источник считается неработающим. */
    private suspend fun ping(url: String): Boolean {
        repeat(ATTEMPTS) { attempt ->
            val ok = withContext(Dispatchers.IO) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    runCatching {
                        val request = Request.Builder()
                            .url(url)
                            .get()
                            .header("User-Agent", USER_AGENT)
                            .build()
                        client.newCall(request).execute().use { true }
                    }.getOrDefault(false)
                } ?: false
            }
            if (ok) return true
            if (attempt < ATTEMPTS - 1) delay(BACKOFF_MS * (attempt + 1))
        }
        return false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 7_000L
        const val READ_TIMEOUT_MS = 7_000L
        const val REQUEST_TIMEOUT_MS = 7_000L
        const val ATTEMPTS = 1
        const val BACKOFF_MS = 0L
        const val TAG = "SourceHealth"
    }
}