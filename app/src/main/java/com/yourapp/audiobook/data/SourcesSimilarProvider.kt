package com.yourapp.audiobook.data

import android.util.Log
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.source.api.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

/**
 * Подбор «похожих книг» из собственных источников приложения.
 *
 * Запрос строится по автору книги (самый надёжный признак) через
 * [AudioBookApplication.searchAll], затем кандидаты ранжируются по текстовой
 * близости описаний: текст описания (и заголовка) превращается в набор признаков
 * (слова без стоп-слов + 3-граммы символов), и книги сортируются по сходству
 * описаний. Обычный алгоритм без моделей и машинного обучения, на чистом Kotlin.
 * Описания кэшируются в [BookDescriptionStore]; кандидаты без описания
 * остаются в конце списка, но не выбрасываются.
 *
 * Сама книга и дубликаты (одно и то же произведение с нескольких источников)
 * отбрасываются. Результат кэшируется в памяти по bookKey.
 */
class SourcesSimilarProvider(private val app: AudioBookApplication) {

    private val cache = LinkedHashMap<String, List<Book>>()
    private val computeMutex = Mutex()
    private val detailSemaphore = Semaphore(2)

    suspend fun similar(bookKey: String, sourceDescription: String? = null): List<Book> =
        computeMutex.withLock {
            val cached = cache[bookKey]
            if (cached != null) {
                cached
            } else {
                val result = try {
                    computeForBook(bookKey, sourceDescription)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "similar($bookKey): ${e.message}")
                    emptyList()
                }
                cache[bookKey] = result
                while (cache.size > MAX_CACHE_ENTRIES) {
                    val first = cache.keys.iterator()
                    if (first.hasNext()) {
                        first.next()
                        first.remove()
                    }
                }
                result
            }
        }

    private suspend fun computeForBook(bookKey: String, sourceDescription: String?): List<Book> {
        val (title, author, genre) = resolveContext(bookKey) ?: run {
            Log.i(TAG, "similar($bookKey) resolveContext=null")
            return emptyList()
        }
        val query = author?.trim()?.takeIf { it.isNotBlank() } ?: title.trim()
        if (query.isBlank()) return emptyList()

        val sourceDesc = sourceDescription?.takeIf { it.isNotBlank() }
            ?: fetchDescription(bookKey, sourceId = null, url = null)

        val normalizedGenre = normalize(genre)

        val authorPool = app.searchAll(query, 0)
            .filter { "${it.sourceId}:${it.id}" != bookKey }
            .filter { !titlesEquivalent(it.title, title) }
            .sortedByDescending { book ->
                if (normalizedGenre.isNotBlank()) genreOverlap(normalizedGenre, normalize(book.genre)) else 0.0
            }
            .let(::dedupeBooks)
            .take(AUTHOR_POOL)

        val genrePool = if (normalizedGenre.isNotBlank()) {
            app.searchAll(genre.orEmpty(), 0)
                .filter { "${it.sourceId}:${it.id}" != bookKey }
                .filter { !titlesEquivalent(it.title, title) }
                .sortedByDescending { genreOverlap(normalizedGenre, normalize(it.genre)) }
                .let(::dedupeBooks)
                .take(GENRE_POOL)
        } else {
            emptyList()
        }

        val pool = dedupeBooks(authorPool + genrePool).take(POOL_SIZE)
        Log.i(TAG, "similar($bookKey) author=${authorPool.size} genre=${genrePool.size} pool=${pool.size}")

        if (pool.isEmpty()) {
            Log.i(TAG, "similar($bookKey) pool=0")
            return emptyList()
        }

        val sourceFeatures = sourceDesc.takeIf { it.isNotBlank() }?.let { textFeatures(it) }
            ?: textFeatures(listOfNotNull(title, author, genre).joinToString(" "))

        val withDescriptions = coroutineScope {
            pool.map { candidate ->
                async {
                    val desc = fetchDescription(
                        bookKey = "${candidate.sourceId}:${candidate.id}",
                        sourceId = candidate.sourceId,
                        url = candidate.url,
                    )
                    candidate to desc
                }
            }.awaitAll()
        }

        val normalizedAuthor = normalize(author)
        val scored = withDescriptions.map { (book, desc) ->
            val features = if (desc.isBlank()) null else textFeatures(desc + " " + book.title)
            val textScore = features?.let { textSimilarity(sourceFeatures, it) } ?: 0.0
            val genreBoost = if (normalizedGenre.isNotBlank()) genreOverlap(normalizedGenre, normalize(book.genre)) else 0.0
            val authorBoost = if (normalizedAuthor.isNotBlank() && normalize(book.author) == normalizedAuthor) 1.0 else 0.0
            val score = textScore * TEXT_WEIGHT + genreBoost * GENRE_WEIGHT + authorBoost * AUTHOR_WEIGHT
            Triple(book, score, textScore)
        }
        val top = scored.sortedByDescending { it.second }.take(3)
            .joinToString("; ") { "«${it.first.title}»=${"%.2f".format(it.second)}" }
        Log.i(TAG, "similar($bookKey) pool=${pool.size} txt=${scored.count { it.third > 0.0 }} | $top")
        return scored
            .sortedWith(compareByDescending<Triple<Book, Double, Double>> { it.second }.thenByDescending { it.third })
            .map { it.first }
            .let(::dedupeBooks)
            .take(MAX_RESULTS)
    }

    /** Возвращает описание книги из кэша или тянет его из источника (с лимитом
     *  одновременных запросов, чтобы не положить источник). */
    private suspend fun fetchDescription(bookKey: String, sourceId: String?, url: String?): String {
        app.descriptionCache.get(bookKey)?.let { return it }
        val resolvedSourceId = sourceId ?: bookKey.substringBefore(":")
        val source = app.sourceRegistry.get(resolvedSourceId) ?: return ""
        val resolvedUrl = url ?: app.bookCache.get(bookKey)?.url
            ?: source.urlForId(bookKey.substringAfter(":"))
        detailSemaphore.acquire()
        try {
            val text = withTimeoutOrNull(DETAIL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runCatching { source.getBookDetails(resolvedUrl).description }.getOrNull()
                }
            } ?: ""
            if (text.isNotBlank()) app.descriptionCache.put(bookKey, text)
            return text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetchDescription($bookKey): ${e.message}")
            return ""
        } finally {
            detailSemaphore.release()
        }
    }

    private suspend fun resolveContext(bookKey: String): Triple<String, String?, String?>? {
        val cached = app.bookCache.get(bookKey)
        if (cached != null && cached.title.isNotBlank()) {
            return Triple(cached.title, cached.author, cached.genre)
        }
        val sourceId = bookKey.substringBefore(":")
        val id = bookKey.substringAfter(":")
        val source = app.sourceRegistry.get(sourceId) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val details = source.getBookDetails(source.urlForId(id))
                Triple(details.book.title, details.book.author, details.book.genre)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Признаки текста: слова без стоп-слов + 3-граммы символов, нормированные по длине. */
    private fun textFeatures(text: String): Map<String, Double> {
        val s = text.lowercase()
        val feats = HashMap<String, Double>()
        for (w in s.split(Regex("[^\\p{L}\\p{N}]+"))) {
            if (w.length >= 3 && w !in STOPWORDS) {
                feats.merge("w:$w", 1.0, Double::plus)
            }
        }
        for (i in 0 until s.length - 2) {
            val gram = s.substring(i, i + 3)
            if (gram.any { it.isLetter() }) {
                feats.merge("c:$gram", GRAM_WEIGHT, Double::plus)
            }
        }
        val norm = sqrt(feats.values.sumOf { it * it }).takeIf { it > 0.0 } ?: return emptyMap()
        return HashMap<String, Double>().apply {
            feats.forEach { (k, v) -> put(k, v / norm) }
        }
    }

    /** Сходство двух наборов признаков (косинусное, в нормированном виде равно скалярному произведению). */
    private fun textSimilarity(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var dot = 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        for ((k, v) in small) {
            large[k]?.let { dot += v * it }
        }
        return dot
    }

    private fun normalize(s: String?): String = s?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""

    /** Убирает повторяющиеся произведения: один ключ источника, либо одинаковое/эквивалентное
     *  название у одного автора с разных источников («Драгорн» и «Драгорн. Том 1»,
     *  «Туман. Сталь» и «Сталь»). Сохраняет первый встреченный экземпляр. */
    private fun dedupeBooks(list: List<Book>): List<Book> {
        val kept = ArrayList<Book>()
        for (book in list) {
            val key = "${book.sourceId}:${book.id}"
            val bookAuthor = normalize(book.author)
            val bookTokens = titleTokens(book.title)
            val exists = kept.any { keptBook ->
                if ("${keptBook.sourceId}:${keptBook.id}" == key) return@any true
                val keptAuthor = normalize(keptBook.author)
                val sameAuthor = bookAuthor.isNotBlank() && keptAuthor.isNotBlank() && bookAuthor == keptAuthor
                sameAuthor && tokensEquivalent(titleTokens(keptBook.title), bookTokens)
            }
            if (!exists) kept.add(book)
        }
        return kept
    }

    /** То же произведение, но название написано по-разному? */
    private fun tokensEquivalent(a: List<String>, b: List<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val baseA = baseTokens(a)
        val baseB = baseTokens(b)
        if (baseA.isNotEmpty() && baseA == baseB) {
            val digitsA = digitsOf(a)
            val digitsB = digitsOf(b)
            if (digitsA.isEmpty() && digitsB.isEmpty()) return true
            if (digitsA.isNotEmpty() && digitsB.isNotEmpty()) return digitsA.first() == digitsB.first()
            val lone = if (digitsA.isEmpty()) digitsB else digitsA
            // «Драгорн» (без номера) обычно и есть 1-й том
            return lone.first() == 1
        }
        // «Туман. Сталь» vs «Сталь»: короткое название — суффикс длинного
        if (a.size != b.size) {
            val (long, short) = if (a.size > b.size) a to b else b to a
            if (short == long.takeLast(short.size)) return true
        }
        return false
    }

    private fun titleTokens(title: String): List<String> =
        title.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    private fun digitsOf(tokens: List<String>): List<Int> =
        tokens.mapNotNull { it.toIntOrNull() }

    private fun baseTokens(tokens: List<String>): List<String> =
        tokens.filter {
            it.toIntOrNull() == null &&
                it !in VOLUME_WORDS &&
                !VOLUME_MIXED.matches(it)
        }

    private fun titlesEquivalent(a: String, b: String): Boolean {
        val ta = titleTokens(a)
        val tb = titleTokens(b)
        return tokensEquivalent(ta, tb)
    }

    /** Степень совпадения жанров: точное равенство → 1.0, вхождение одного в другой → 0.8,
     *  общий значимый токен («фэнтези», «фантастика»…) → 0.5, иначе 0. */
    private fun genreOverlap(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if ((a.contains(b) || b.contains(a)) && a.length >= 4 && b.length >= 4) return 0.8
        val tokensA = a.split(" ").filter { it.length >= 4 }.toSet()
        val tokensB = b.split(" ").filter { it.length >= 4 }.toSet()
        if (tokensA.isNotEmpty() && tokensB.isNotEmpty() && tokensA.any { it in tokensB }) return 0.5
        return 0.0
    }

    private companion object {
        const val TAG = "SourcesSimilar"
        const val MAX_RESULTS = 60
        const val POOL_SIZE = 12
        const val AUTHOR_POOL = 8
        const val GENRE_POOL = 6
        const val MAX_CACHE_ENTRIES = 200
        const val DETAIL_TIMEOUT_MS = 10_000L
        const val GRAM_WEIGHT = 0.3
        const val TEXT_WEIGHT = 0.55
        const val GENRE_WEIGHT = 0.30
        const val AUTHOR_WEIGHT = 0.15

        val VOLUME_WORDS = setOf(
            "том", "книга", "книг", "часть", "выпуск", "сборник", "серия", "аудиокнига", "глава",
        )
        val VOLUME_MIXED = Regex("^[a-zа-я]+\\d+$|^\\d+[a-zа-я]+$")

        val STOPWORDS = setOf(
            "что", "как", "все", "она", "это", "его", "только", "чуть", "чтобы", "уже",
            "еще", "был", "при", "может", "когда", "также", "она", "они", "если", "нет",
            "этот", "себя", "них", "ему", "ей", "ним", "нам", "вас", "или", "либо",
            "где", "там", "тут", "вот", "между", "без", "под", "над", "перед", "через",
            "из", "от", "до", "по", "ко", "об", "о", "у", "в", "с", "на", "за", "для",
        )
    }
}