package com.yourapp.audiobook.source.izibuk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IziBukParserTest {

    private val sampleListHtml = """
        <html><body>
        <div id="books_list">
            <div class="_ccb9b7 _5c0f1a" id="book140288" data-serie-index="3">
                <div class="_680f12"><a href="/genre1">Фантастика, фэнтези</a></div>
                <a class="_bce453" href="/art140288">
                    <img class="_76d12c" src="https://r2.audioknigi.xyz/f135d49074ccf81c/pic/bab1ca52d37eeedc.jpg" alt="img"/>
                </a>
                <div class="_802db0">
                    <div class="_3dc935">
                        <a href="/art140288" class="_3dc935">Вакансия для злодея</a>
                        <span class="_08dd3a" style="display: none">(09:39)</span>
                    </div>
                    <div class="_eeab32"><span class="_a6370b _81deee"></span>
                        <a href="/author24404">Лилия Альшер</a>
                    </div>
                    <div class="_eeab32"><span class="_a6370b _8ff5b6"></span>
                        <a href="/reader4959">Лина Ветлицкая</a>
                    </div>
                    <div class="_eeab32"><span class="_a6370b _1f6354"></span>
                        <span class="_08dd3a">9 ч. 39 мин.</span>
                    </div>
                </div>
            </div>
            <div class="_ccb9b7 _5c0f1a" id="book140287">
                <div class="_680f12"><a href="/genre3">Роман, проза</a></div>
                <a class="_bce453" href="/art140287">
                    <img class="_76d12c" src="https://r2.audioknigi.xyz/7e6aed1c11bd95b1/pic/9b690cf3fa74cf53.jpg" alt="img"/>
                </a>
                <div class="_802db0">
                    <div class="_3dc935">
                        <a href="/art140287" class="_3dc935">Книжный магазинчик «Утешение»</a>
                    </div>
                    <div class="_eeab32"><span class="_a6370b _81deee"></span>
                        <a href="/author29568">Джи Хен Лим</a>
                    </div>
                    <div class="_eeab32"><span class="_a6370b _8ff5b6"></span>
                        <a href="/reader4594">Максим Полтавский</a>
                    </div>
                    <div class="_eeab32"><span class="_a6370b _1f6354"></span>
                        <span class="_08dd3a">3 ч. 9 мин.</span>
                    </div>
                </div>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    private val sampleBookHtml = """
        <html><body>
        <div itemscope itemtype="http://schema.org/Book">
            <div class="_0a4964">
                <div class="_c10a44"><span itemprop="name">Вакансия для злодея</span></div>
            </div>
            <div class="_79758c _27cf95">
                <div class="_8f5765">Автор: <span itemprop="author"><a href="/author24404">Лилия Альшер</a></span></div>
                <div class="_8f5765">Читает: <a href="/reader4959">Лина Ветлицкая</a></div>
                <div class="_8f5765">Время: 9 ч. 39 мин.</div>
            </div>
            <div class="_6f05d7 _5c0f1a">
                <div class="_306524">
                    <img src="https://r2.audioknigi.xyz/f135d49074ccf81c/pic/bab1ca52d37eeedc.jpg" alt="Вакансия для злодея"/>
                </div>
                <span itemprop="description">Адалин и подумать не могла, что её бывший жених окажется её деканом. Это история о выборе.</span>
            </div>
        </div>
        <script>
        domReady(function() {
         var player = new XSPlayer({"id":140288,"blocked":false,"mp3_url_prefix":"r2.audioknigi.xyz\/f135d49074ccf81c\/audio","sign":"?md5=nbwuud78iMRYWej6_vGhLQ&expires=1786137680","tracks":[[4685909,"Вакансия для злодея 01",2604,0,"vakansija-dlja-zlodeja-01.mp3"],[4685910,"Вакансия для злодея 02",3109,0,"vakansija-dlja-zlodeja-02.mp3"],[4685919,"Вакансия для злодея 11",448,0,"vakansija-dlja-zlodeja-11.mp3"]],"speeds":[0.5,1,2],"progress":false,"duration":34747});
         var faves = new PageFaves(140288);
        });
        </script>
        </body></html>
    """.trimIndent()

    private val sampleGenresHtml = """
        <html><body>
        <div id="topmenu"><div class="_56acc4">
            <a class="_d443a3" href="/genre1?subcategory=3">Любовное Фэнтези</a>
            <a class="_d443a3" href="/genre1?subcategory=1">Попаданцы</a>
            <a class="_d443a3" href="/genre1?subcategory=4">S.T.A.L.K.E.R.</a>
        </div></div>
        <div class="_c2b650">
            <a href="/genre1" class="_15584a">
                <div class="_93426d">
                    <span class="_28f7ab">Фантастика, фэнтези</span>
                    <span class="_94566d">43 202 книг</span>
                </div>
            </a>
            <a href="/genre24" class="_15584a">
                <div class="_93426d">
                    <span class="_28f7ab">Фанфики</span>
                    <span class="_94566d">73 книги</span>
                </div>
            </a>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesGenres() {
        val genres = IziBukSource.parseGenres(sampleGenresHtml, "https://pda.izib.uk/")
        assertEquals(5, genres.size)

        val first = genres[0]
        assertEquals("Фантастика, фэнтези", first.name)
        assertEquals("https://pda.izib.uk/genre1", first.url)
        assertEquals(43202L, first.bookCount)

        assertEquals("Фанфики", genres[1].name)
        assertEquals("https://pda.izib.uk/genre24", genres[1].url)
        assertEquals(73L, genres[1].bookCount)

        assertEquals("Любовное Фэнтези", genres[2].name)
        assertEquals("https://pda.izib.uk/genre1?subcategory=3", genres[2].url)
        assertEquals("Попаданцы", genres[3].name)
        assertEquals("https://pda.izib.uk/genre1?subcategory=1", genres[3].url)
        assertEquals("S.T.A.L.K.E.R.", genres[4].name)
        assertEquals("https://pda.izib.uk/genre1?subcategory=4", genres[4].url)
    }

    @Test
    fun parsesBookList() {
        val books = IziBukSource.parseBookList(sampleListHtml, "izibuk", "https://pda.izib.uk/")
        assertEquals(2, books.size)

        val first = books[0]
        assertEquals("izibuk", first.sourceId)
        assertEquals("140288", first.id)
        assertEquals("Вакансия для злодея", first.title)
        assertEquals("https://pda.izib.uk/art140288", first.url)
        assertEquals("https://r2.audioknigi.xyz/f135d49074ccf81c/pic/bab1ca52d37eeedc.jpg", first.coverUrl)
        assertEquals("Лилия Альшер", first.author)
        assertEquals("Лина Ветлицкая", first.reader)
        assertEquals("9 ч. 39 мин.", first.durationText)
        assertEquals("Фантастика, фэнтези", first.genre)

        assertEquals("Книжный магазинчик «Утешение»", books[1].title)
        assertEquals("3 ч. 9 мин.", books[1].durationText)
        assertEquals("Максим Полтавский", books[1].reader)
    }

    @Test
    fun parsesPlayerTracks() {
        val tracks = IziBukSource.parseTracks(sampleBookHtml)
        assertEquals(3, tracks.size)

        val first = tracks[0]
        assertEquals("Вакансия для злодея 01", first.title)
        assertEquals(2604, first.durationSeconds)
        assertEquals(
            "https://r2.audioknigi.xyz/f135d49074ccf81c/audio/vakansija-dlja-zlodeja-01.mp3?md5=nbwuud78iMRYWej6_vGhLQ&expires=1786137680",
            first.url,
        )
        assertEquals("Вакансия для злодея 11", tracks[2].title)
    }

    @Test
    fun parsesDetails() {
        val details = IziBukSource.parseDetails(sampleBookHtml, "izibuk", "https://pda.izibuk/art140288")!!

        assertEquals("Вакансия для злодея", details.book.title)
        assertEquals("140288", details.book.id)
        assertEquals("Лилия Альшер", details.book.author)
        assertEquals("Лина Ветлицкая", details.book.reader)
        assertEquals("9 ч. 39 мин.", details.book.durationText)
        assertEquals("https://r2.audioknigi.xyz/f135d49074ccf81c/pic/bab1ca52d37eeedc.jpg", details.book.coverUrl)
        assertTrue(details.description!!.startsWith("Адалин и подумать не могла"))
        assertEquals(3, details.tracks.size)
    }

    @Test
    fun parsesSeriesInDetails() {
        val html = sampleBookHtml.replace(
            "</body></html>",
            """
            <div class="_79758c _49d1b4">
                <div class="_40d1c3">Серия: <a href="/serie493">Серия про злодея</a>:</div>
                <div class="_0a3ec5">
                    <span class="_77155e">1.</span>
                    <a href="/art30923">Книга первая</a>
                    (Читает <a href="/reader2869">Александр Клюквин</a>)
                </div>
                <div class="_0a3ec5">
                    <span class="_77155e">1.5.</span>
                    <a href="/art23889">Промежуточная книга</a>
                </div>
                <div class="_0a3ec5"><strong>Вакансия для злодея</strong></div>
                <div class="_0a3ec5">
                    <span class="_77155e">0.</span>
                    <a href="/art23887">Приквел</a>
                </div>
            </div>
            </body></html>
            """.trimIndent(),
        )
        val details = IziBukSource.parseDetails(html, "izibuk", "https://pda.izibuk/art140288")!!

        assertEquals("Серия про злодея", details.book.seriesTitle)
        assertEquals("https://pda.izibuk/serie493", details.book.seriesUrl)
        assertEquals(null, details.book.seriesIndex)

        assertEquals(3, details.seriesBooks.size)
        val first = details.seriesBooks[0]
        assertEquals("Книга первая", first.title)
        assertEquals("https://pda.izibuk/art30923", first.url)
        assertEquals(1, first.seriesIndex)
        assertEquals("Серия про злодея", first.seriesTitle)
        assertEquals("Александр Клюквин", first.reader)
        assertEquals(null, details.seriesBooks[1].seriesIndex)
        assertEquals(null, details.seriesBooks[2].seriesIndex)
    }

    @Test
    fun returnsEmptyTracksWhenBlocked() {
        val blockedHtml = sampleBookHtml.replace("\"blocked\":false", "\"blocked\":true")
        assertTrue(IziBukSource.parseTracks(blockedHtml).isEmpty())
    }

    @Test
    fun returnsEmptyListForEmptyPage() {
        assertTrue(IziBukSource.parseBookList("<html><body><div id='books_list'></div></body></html>", "izibuk", "https://pda.izib.uk/").isEmpty())
    }
}
