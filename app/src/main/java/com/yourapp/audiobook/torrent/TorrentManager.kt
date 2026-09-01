package com.yourapp.audiobook.torrent

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.remove_flags_t

data class TorrentState(
    val bookKey: String,
    val percent: Int = 0,
    val downloading: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
)

/**
 * Менеджер торрент-загрузок на базе libtorrent4j (libtorrent 2.1).
 * Одна сессия на приложение; каждая книга скачивается в отдельную папку.
 */
class TorrentManager(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessionLock = Any()
    private var session: SessionManager? = null

    private fun session(): SessionManager? {
        if (session == null) {
            synchronized(sessionLock) {
                if (session == null) {
                    session = runCatching {
                        val settings = org.libtorrent4j.SettingsPack()
                        // Порт 6881 по умолчанию может быть запрещён на Android (bind: Permission denied),
                        // а IPv6 часто недоступен в мобильных сетях — слушаем только IPv4 на случайном порту.
                        settings.listenInterfaces("0.0.0.0:0")
                        SessionManager().also { it.start(org.libtorrent4j.SessionParams(settings)) }
                    }.getOrNull()
                    session?.let { sm ->
                        Log.d(TAG, "session created, running=${sm.isRunning()}")
                        runCatching {
                            sm.addListener(object : AlertListener {
                                override fun types(): IntArray = intArrayOf(
                                    AlertType.TRACKER_ERROR.swig(),
                                    AlertType.TRACKER_WARNING.swig(),
                                    AlertType.LISTEN_FAILED.swig(),
                                    AlertType.PEER_ERROR.swig(),
                                )

                                override fun alert(alert: Alert<*>) {
                                    Log.d(TAG, "ALERT ${alert.javaClass.simpleName}: ${alert.message()}")
                                }
                            })
                        }
                    }
                }
            }
        }
        return session
    }

    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val _states = MutableStateFlow<Map<String, TorrentState>>(emptyMap())
    val states: StateFlow<Map<String, TorrentState>> = _states.asStateFlow()

    /** Торрент-движок доступен (нативная библиотека загрузилась). */
    fun available(): Boolean = session() != null

    /** Начинает скачивание торрента в [saveDir]. Обновляет [states] каждую секунду. */
    fun start(bookKey: String, torrentBytes: ByteArray, saveDir: File) {
        val sm = session() ?: return
        if (handles.containsKey(bookKey)) return
        _states.update { it + (bookKey to TorrentState(bookKey, downloading = true)) }
        scope.launch {
            try {
                val ti = withContext(Dispatchers.IO) { TorrentInfo(torrentBytes) }
                if (!ti.isValid()) throw IllegalArgumentException("Некорректный торрент-файл")
                withContext(Dispatchers.IO) {
                    if (!sm.isRunning()) sm.start()
                    runCatching { sm.startDht() }
                    sm.download(ti, saveDir)
                }
                val handle = waitForHandle(sm, ti)
                handles[bookKey] = handle
                poll(bookKey, handle)
            } catch (e: Exception) {
                _states.update {
                    it + (bookKey to TorrentState(bookKey, error = "Не удалось начать скачивание: ${e.message}"))
                }
                handles.remove(bookKey)
            }
        }
    }

    /** Останавливает скачивание книги и удаляет частично скачанные файлы. */
    fun cancel(bookKey: String) {
        val sm = session() ?: return
        val handle = handles.remove(bookKey) ?: run {
            _states.update { it - bookKey }
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { sm.remove(handle, remove_flags_t.all()) }
            }
            _states.update { it - bookKey }
        }
    }

    fun cancelAll() {
        handles.keys.toList().forEach(::cancel)
    }

    private suspend fun waitForHandle(sm: SessionManager, ti: TorrentInfo): TorrentHandle {
        val hash = withContext(Dispatchers.IO) { ti.infoHash() }
        repeat(100) {
            val handle = withContext(Dispatchers.IO) { sm.find(hash) }
            if (handle != null && handle.isValid()) return handle
            delay(100)
        }
        throw IllegalStateException("Торрент не добавлен в сессию")
    }

    private suspend fun poll(bookKey: String, handle: TorrentHandle) {
        var lastLogSec = -1L
        var lastPercent = -1
        while (scope.isActive) {
            val status = withContext(Dispatchers.IO) { handle.status() }
            val percent = (status.progress() * 100).toInt().coerceIn(0, 100)
            val finished = status.isSeeding || status.isFinished || percent >= 100
            val nowSec = System.currentTimeMillis() / 1000
            if (nowSec - lastLogSec >= 5 || finished) {
                lastLogSec = nowSec
                if (percent != lastPercent || finished) {
                    lastPercent = percent
                    Log.d(TAG, "book=${bookKey.takeLast(40)} state=${status.state()} percent=$percent peers=${status.numPeers()} rate=${status.downloadRate()}")
                }
            }
            _states.update {
                it + (bookKey to TorrentState(
                    bookKey = bookKey,
                    percent = percent,
                    downloading = !finished,
                    finished = finished,
                ))
            }
            if (finished) {
                withContext(Dispatchers.IO) { runCatching { handle.pause() } }
                break
            }
            delay(1000)
        }
    }

    private companion object {
        const val TAG = "TorrentManager"
    }
}