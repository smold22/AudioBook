package com.yourapp.audiobook.source.extra

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Диагностика торрент-раздач: извлекает announce-трекер из .torrent,
 * делает announce запрос (HTTP и UDP) и показывает число сидов/пиров.
 */
class TorrentTrackerCheckTest {

    @Test
    fun checkRuKnigaMeSwarm() = runBlocking {
        checkTorrent("ruknigame", RuKnigaMeSource())
    }

    @Test
    fun checkTAudioknigiMp3Swarm() = runBlocking {
        checkTorrent("taudioknigimp3", TAudioknigiMp3Source())
    }

    private suspend fun checkTorrent(label: String, source: com.yourapp.audiobook.source.api.AudiobookSource) {
        try {
            val home = source.home(1)
            val book = home.firstOrNull() ?: run { println("$label: no books"); return }
            val details = source.getBookDetails(book.url)
            val torrentUrl = details.torrentUrl ?: run { println("$label: no torrent url"); return }
            val bytes = source.fetchTorrentBytes(torrentUrl)
                ?: run { println("$label: no torrent bytes"); return }
            val bencode = Bencode(bytes)
            val announce = (bencode.root["announce"] as? ByteArray)?.toString(Charsets.ISO_8859_1)
            val infoRaw = bencode.infoRaw
            val infoHash = MessageDigest.getInstance("SHA-1").digest(infoRaw)
            println("$label: announce=$announce infoHash=${hex(infoHash)}")
            if (announce == null) return
            if (announce.startsWith("http")) {
                announceHttp(label, announce, infoHash)
            } else if (announce.startsWith("udp")) {
                announceUdp(label, announce, infoHash)
            } else {
                println("$label: unsupported tracker $announce")
            }
        } catch (e: Exception) {
            println("$label: ERROR ${e.javaClass.simpleName}: ${e.message?.take(150)}")
        }
    }

    private fun announceHttp(label: String, announce: String, infoHash: ByteArray) {
        val peerId = "AUDBOOKTEST".padEnd(20, '1')
        val url = "$announce?info_hash=${urlEncode(infoHash)}&peer_id=${urlEncode(peerId.toByteArray())}" +
            "&port=6881&uploaded=0&downloaded=0&left=1&compact=1&event=started&numwant=50"
        val response = URL(url).readBytes()
        val bencode = Bencode(response)
        val complete = (bencode.root["complete"] as? Long) ?: -1
        val incomplete = (bencode.root["incomplete"] as? Long) ?: -1
        val peers = (bencode.root["peers"] as? ByteArray)?.size?.div(6) ?: 0
        val peers6 = (bencode.root["peers6"] as? ByteArray)?.size?.div(18) ?: 0
        val failure = (bencode.root["failure reason"] as? ByteArray)?.toString(Charsets.ISO_8859_1)
        println("$label: HTTP tracker -> seeders=$complete leechers=$incomplete peers=$peers peers6=$peers6 failure=$failure")
    }

    private fun announceUdp(label: String, announce: String, infoHash: ByteArray) {
        val hostPort = announce.removePrefix("udp://").substringBefore("/")
        val host = hostPort.substringBefore(":")
        val port = hostPort.substringAfter(":", "6969").toInt()
        val socket = DatagramSocket()
        socket.soTimeout = 8000
        val random = Random()
        try {
            val txConnect = random.nextInt()
            val connectReq = ByteBuffer.allocate(16)
                .putLong(0x41727101980L)
                .putInt(0)
                .putInt(txConnect)
                .array()
            socket.send(DatagramPacket(connectReq, connectReq.size, InetAddress.getByName(host), port))
            val buf = ByteArray(64)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            val response = packet.data.copyOf(packet.length)
            if (readInt(response, 0) != 0 || readInt(response, 4) != txConnect) {
                println("$label: UDP tracker connect failed")
                return
            }
            val connectionId = readLong(response, 8)
            val txAnnounce = random.nextInt()
            val left = 1000L
            val announceReq = ByteBuffer.allocate(98)
                .putLong(connectionId)
                .putInt(1)
                .putInt(txAnnounce)
                .put(infoHash)
                .put("AUDBOOKTEST".padEnd(20, '1').toByteArray())
                .putLong(0) // downloaded
                .putLong(left)
                .putLong(0) // uploaded
                .putInt(0) // event = none
                .putInt(0) // ip
                .putInt(random.nextInt()) // key
                .putInt(-1) // numwant
                .putShort(6881)
                .array()
            socket.send(DatagramPacket(announceReq, announceReq.size, InetAddress.getByName(host), port))
            val buf2 = ByteArray(2048)
            val packet2 = DatagramPacket(buf2, buf2.size)
            socket.receive(packet2)
            val resp2 = packet2.data.copyOf(packet2.length)
            if (readInt(resp2, 0) != 1 || readInt(resp2, 4) != txAnnounce) {
                println("$label: UDP tracker announce failed")
                return
            }
            val interval = readInt(resp2, 8)
            val leechers = readInt(resp2, 12)
            val seeders = readInt(resp2, 16)
            val peers = (resp2.size - 20) / 6
            println("$label: UDP tracker -> seeders=$seeders leechers=$leechers peers=$peers interval=$interval")
        } catch (e: Exception) {
            println("$label: UDP tracker error: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
        } finally {
            socket.close()
        }
    }

    private fun urlEncode(bytes: ByteArray): String =
        bytes.joinToString("") { "%${String.format("%02X", it)}" }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format("%02x", it) }

    private fun readInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) shl 24 or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun readLong(data: ByteArray, offset: Int): Long =
        (readInt(data, offset).toLong() shl 32) or (readInt(data, offset + 4).toLong() and 0xFFFFFFFFL)
}

private class Bencode(data: ByteArray) {
    val root: Map<String, Any>
    val infoRaw: ByteArray

    init {
        val pos = intArrayOf(0)
        root = parse(data, pos) as Map<String, Any>
        val idx = indexOf(data, "4:info".toByteArray())
        if (idx < 0) throw IllegalArgumentException("no info key")
        val start = idx + 6
        val p = intArrayOf(start)
        parse(data, p)
        infoRaw = data.copyOfRange(start, p[0])
    }

    private fun parse(d: ByteArray, p: IntArray): Any {
        return when (d[p[0]].toInt().toChar()) {
            'd' -> {
                p[0]++
                val m = mutableMapOf<String, Any>()
                while (d[p[0]].toInt().toChar() != 'e') {
                    val key = parse(d, p) as ByteArray
                    m[String(key, Charsets.ISO_8859_1)] = parse(d, p)
                }
                p[0]++
                m
            }
            'l' -> {
                p[0]++
                val l = mutableListOf<Any>()
                while (d[p[0]].toInt().toChar() != 'e') l.add(parse(d, p))
                p[0]++
                l
            }
            'i' -> {
                p[0]++
                val sb = StringBuilder()
                while (d[p[0]].toInt().toChar() != 'e') {
                    sb.append(d[p[0]].toInt().toChar())
                    p[0]++
                }
                p[0]++
                sb.toString().toLong()
            }
            else -> {
                val len = StringBuilder()
                while (d[p[0]].toInt().toChar().isDigit()) {
                    len.append(d[p[0]].toInt().toChar())
                    p[0]++
                }
                p[0]++
                val l = len.toString().toInt()
                val b = d.copyOfRange(p[0], p[0] + l)
                p[0] += l
                b
            }
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}