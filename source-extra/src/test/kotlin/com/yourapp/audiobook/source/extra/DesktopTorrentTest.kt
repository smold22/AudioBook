package com.yourapp.audiobook.source.extra

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo

/**
 * Контрольный тест на JVM (Windows, нативная jlibtorrent): воспроизводит
 * добавление торрента из t-audioknigimp3 и проверяет, что трекеры аннонсят
 * и появляются пиры. Нужен для сравнения с поведением на телефоне.
 */
class DesktopTorrentTest {

    @Test
    fun downloadFromTAudioknigi() = runBlocking {
        val s = TAudioknigiMp3Source()
        val book = s.home(1).first()
        val details = s.getBookDetails(book.url)
        val bytes = s.fetchTorrentBytes(details.torrentUrl!!)!!
        val ti = TorrentInfo(bytes)
        println("TORRENT name=${ti.name()}")

        val sm = SessionManager()
        sm.start()
        val saveDir = File(System.getProperty("java.io.tmpdir"), "jlibtest")
        saveDir.deleteRecursively()
        saveDir.mkdirs()
        sm.download(ti, saveDir)
        val hash = ti.infoHash()
        var peersFound = false
        for (i in 1..90) {
            val handle = sm.find(hash)
            if (handle != null && handle.isValid()) {
                val status = handle.status()
                val trackers = runCatching { handle.trackers().flatMap { e -> e.endpoints() } }
                    .getOrDefault(emptyList())
                println("STEP $i state=${status.state()} pct=${"%.1f".format(status.progress() * 100)} " +
                    "peers=${status.numPeers()} rate=${status.downloadRate()} trackers=$trackers")
                if (status.numPeers() > 0 || status.downloadRate() > 0) {
                    println("PEERS FOUND after ${i}s!")
                    peersFound = true
                    break
                }
            } else {
                println("STEP $i no handle yet")
            }
            delay(1000)
        }
        println("RESULT peersFound=$peersFound")
        sm.stop()
    }
}