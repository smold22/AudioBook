package com.yourapp.audiobook.source.extra

import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URLEncoder

/**
 * Источник https://aume.ru — аудиокниги в формате DLE.
 * Треки лежат прямо в странице книги: <!--dle_audio_begin--><div class="dleaudioplayer">
 * <li data-title data-url>. У части книг плеер отключён на самом сайте — такие
 * возвращаются без треков.
 */
class AumeSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "aume"
    override val name = "AuMe"
    override val baseUrl = "https://aume.ru"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getPage(if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"))

    override suspend fun search(query: String, page: Int): List<Book> {
        val q = URLEncoder.encode(query, "UTF-8")
        val start = if (page <= 1) "" else "&search_start=${(page - 1) * 10 + 1}"
        return parseBooks(
            getPage("$baseUrl/index.php?do=search&subaction=search&story=$q$start"),
        )
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val doc = Jsoup.parse(getHtml(client, url), url)
        val title = doc.selectFirst("h1.ftitle")?.text()?.trim() ?: ""
        val text = doc.selectFirst("div.text-justify")
        val cover = text?.selectFirst("img")?.attr("src")?.let(::absUrl).toNullIfBlank()
        val genre = text?.selectFirst("b")?.select("a[href]")?.eachText()
            ?.joinToString(" / ").toNullIfBlank()
        val author = labelValue(doc, "Автор")
        val reader = labelValue(doc, "Читает", "Исполнитель", "Чтец", "Озвучивает")
        val duration = labelValue(doc, "Время звучания", "Время")?.toNullIfBlank()
        val seriesB = doc.select("div.text-justify b").firstOrNull { it.text().contains("Цикл") }
        val seriesTitle = seriesB?.text()
            ?.let { Regex("""Цикл\s*["«]([^"»]+)["»]""").find(it)?.groupValues?.get(1) }
        val seriesIndex = seriesB?.text()
            ?.let { Regex("""(\d+)\s*\.\s*$""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val book = Book(
            sourceId = id,
            id = url,
            title = title,
            url = url,
            coverUrl = cover,
            author = author,
            reader = reader,
            durationText = duration,
            genre = genre,
            seriesTitle = seriesTitle,
            seriesIndex = seriesIndex,
        )
        val related = doc.selectFirst("h3:contains(Похожие)")?.parent()
            ?.select("div.blog_item_block")
            ?.mapNotNull(::parseCard)
            .orEmpty()
        return BookDetails(
            book = book,
            description = description(text),
            tracks = parseTracks(doc),
            related = related,
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else url.trimEnd('/') + "/page/$page/"
        return parseBooks(getPage(target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, baseUrl), baseUrl)
        return doc.select(".dropdown-menu a[href]").mapNotNull { link ->
            val href = link.absUrl("href").takeIf { it.startsWith("$baseUrl/") }
                ?: return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Genre(name = name, url = href)
        }.distinctBy { it.url }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "div.blog_item_block")

    /** Страница с обработкой 404: запрос страницы за пределами списка — конец списка. */
    private suspend fun getPage(url: String): String = runCatching {
        getHtml(client, url)
    }.getOrElse { e ->
        if (e.message?.contains("HTTP 404") == true) "" else throw e
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("div.blog_item_block").mapNotNull(::parseCard)
    }

    private fun parseCard(item: Element): Book? {
        val link = item.selectFirst(".content-title h3 a") ?: return null
        val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return null
        val title = link.text().trim().takeIf { it.isNotBlank() } ?: return null
        val cover = item.selectFirst(".simg img")?.attr("src")?.let(::absUrl).toNullIfBlank()
        val genre = item.selectFirst(".dtl_blog_item_text b")?.text().toNullIfBlank()
        return Book(
            sourceId = id,
            id = href,
            title = title,
            url = href,
            coverUrl = cover,
            genre = genre,
        )
    }

    private fun parseTracks(doc: Document): List<AudioTrack> =
        doc.select(".dleaudioplayer li[data-url]").mapIndexedNotNull { index, li ->
            val url = li.attr("data-url").toNullIfBlank() ?: return@mapIndexedNotNull null
            val raw = li.attr("data-title").toNullIfBlank() ?: return@mapIndexedNotNull null
            // Имя файла вида "TIMESTAMP_01-название-книги.mp3" или "01 - Глава N.mp3".
            val cleaned = raw.substringAfter('_').substringBeforeLast('.')
            val num = Regex("""^(\d{1,3})(?=[\s._-]|$)""").find(cleaned)?.groupValues?.get(1)
            val readable = if (cleaned.contains(" - ")) {
                cleaned.substringAfter(" - ").trim().takeIf { it.isNotBlank() }
            } else {
                null
            }
            AudioTrack(
                title = readable ?: (num?.let { "%02d".format(it.toInt()) } ?: "%02d".format(index + 1)),
                url = url,
            )
        }

    /** Текст метки вида <b>Автор:</b> значение <a>…</a><br><b>Следующая метка:</b>. */
    private fun labelValue(doc: Document, vararg labels: String): String? {
        val label = doc.select("div.text-justify b").firstOrNull { b ->
            labels.any { b.text().trim().startsWith(it) }
        } ?: return null
        val sb = StringBuilder()
        var node = label.nextSibling()
        while (node != null) {
            when (node) {
                is TextNode -> sb.append(node.text())
                is Element -> when {
                    node.tagName() == "br" -> break
                    node.tagName() == "b" -> {
                        if (node.selectFirst("a") != null) sb.append(node.text()) else break
                    }
                    else -> sb.append(node.text())
                }
                else -> Unit
            }
            node = node.nextSibling()
        }
        return sb.toString().trim().toNullIfBlank()
    }

    private fun description(text: Element?): String? {
        if (text == null) return null
        val clone = text.clone()
        clone.selectFirst("b")?.remove()
        var value = clone.text().trim()
        value = value.substringBefore("Читать:").substringBefore("Слушать:").trim()
        return value.toNullIfBlank()
    }

    private fun absUrl(value: String): String = when {
        value.startsWith("http") -> value
        value.startsWith("//") -> "https:" + value
        value.startsWith("/") -> baseUrl + value
        else -> baseUrl + "/" + value
    }
}