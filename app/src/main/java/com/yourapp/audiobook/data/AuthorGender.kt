package com.yourapp.audiobook.data

import com.yourapp.audiobook.source.api.Book

object AuthorGender {

    private val FEMALE_SURNAME_SUFFIXES = listOf(
        "ова", "ева", "ина", "ына", "ая", "яя", "ская", "цкая",
    )

    private val FEMALE_FIRST_NAMES = setOf(
        "ада", "алевтина", "александра", "алёна", "алена", "алина", "алиса", "алла", "альбина", "амина",
        "анастасия", "ангелина", "анжела", "анжелика", "анна", "антонина", "арина", "ася",
        "агата", "агафья", "белла", "валентина", "валерия", "варвара", "василиса", "вера", "вероника", "виктория",
        "вирджиния", "галина", "дана", "дарья", "диана", "дина", "джейн", "джоан", "донна",
        "евгения", "екатерина", "елена", "елизавета", "элис", "эмили", "энн", "жанна",
        "зарина", "зинаида", "зоя", "инна", "ирина", "карина", "кира", "кейт", "ксения",
        "лариса", "лесли", "лидия", "лилия", "лина", "любовь", "люси", "людмила",
        "майя", "маргарет", "маргарита", "марина", "мария", "марьяна", "мэри", "милана", "мирослава", "мишель",
        "надежда", "натали", "наталья", "наталия", "нелли", "нина", "нонна",
        "оксана", "олеся", "ольга", "паула", "полина", "раиса", "регина", "римма", "роза", "руслана",
        "светлана", "снежана", "софия", "софья", "стефания", "таисия", "тамара", "татьяна",
        "ульяна", "фаина", "харпер", "эльвира", "эмилия", "эмма", "эшли", "юлиана", "юлия", "яна", "ярослава",
    )

    /**
     * Приблизительное определение пола автора по имени и фамилии.
     * Может ошибаться на редких именах и иностранных фамилиях.
     */
    fun isFemaleAuthor(name: String): Boolean {
        val parts = name.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return false
        val surname = normalize(parts.last())
        if (FEMALE_SURNAME_SUFFIXES.any { surname.endsWith(it) }) return true
        val firstName = normalize(parts.first())
        return firstName in FEMALE_FIRST_NAMES
    }

    fun filterFemale(books: List<Book>, enabled: Boolean): List<Book> =
        if (enabled) books.filterNot { book ->
            book.author != null && isFemaleAuthor(book.author!!)
        } else {
            books
        }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace('ё', 'е')
            .replace(Regex("""[^a-zа-яё]"""), "")
}
