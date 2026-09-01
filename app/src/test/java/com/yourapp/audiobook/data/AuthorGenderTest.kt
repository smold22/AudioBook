package com.yourapp.audiobook.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorGenderTest {

    @Test
    fun detectsRussianFemaleSurname() {
        assertTrue(AuthorGender.isFemaleAuthor("Наталья Ломакина"))
        assertTrue(AuthorGender.isFemaleAuthor("Иванова Елена"))
        assertTrue(AuthorGender.isFemaleAuthor("Анна Петрова"))
    }

    @Test
    fun detectsTransliteratedEnglishNamesToCyrillic() {
        assertTrue(AuthorGender.isFemaleAuthor("Лиза Бетт"))
        assertTrue(AuthorGender.isFemaleAuthor("Лиза Смит"))
        assertTrue(AuthorGender.isFemaleAuthor("Анна Каренина"))
        assertTrue(AuthorGender.isFemaleAuthor("Anna Karenina"))
        assertTrue(AuthorGender.isFemaleAuthor("Margaret Mitchell"))
        assertTrue(AuthorGender.isFemaleAuthor("Джейн Остин"))
        assertTrue(AuthorGender.isFemaleAuthor("Элиз Берт"))
        assertTrue(AuthorGender.isFemaleAuthor("Долорес Клэйборн"))
        assertTrue(AuthorGender.isFemaleAuthor("Оливия Грин"))
        assertTrue(AuthorGender.isFemaleAuthor("Софи Тёрнер"))
        assertTrue(AuthorGender.isFemaleAuthor("Эмили Уотсон"))
        assertTrue(AuthorGender.isFemaleAuthor("Эмма Стоун"))
    }

    @Test
    fun keepsMales() {
        assertFalse(AuthorGender.isFemaleAuthor("Иван Иванов"))
        assertFalse(AuthorGender.isFemaleAuthor("Алексей Смирнов"))
        assertFalse(AuthorGender.isFemaleAuthor("Николай Романов"))
    }
}
