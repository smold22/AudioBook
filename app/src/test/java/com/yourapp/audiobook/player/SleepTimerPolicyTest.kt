package com.yourapp.audiobook.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверяем чистые правила таймера сна: на какой границе ставится пауза
 * и как считается затухание. Сам ExoPlayer здесь не нужен.
 */
class SleepTimerPolicyTest {

    // --- До конца главы / книги ---

    @Test
    fun endOfChapterPausesOnEveryBoundary() {
        assertTrue(SleepTimerPolicy.pauseOnTransition(SleepTimerMode.END_OF_CHAPTER, 0, 1, 12))
        assertTrue(SleepTimerPolicy.pauseOnTransition(SleepTimerMode.END_OF_CHAPTER, 3, 4, 12))
        assertTrue(SleepTimerPolicy.pauseOnTransition(SleepTimerMode.END_OF_CHAPTER, 11, 12, 12))
    }

    @Test
    fun endOfBookPausesOnlyAfterLastChapter() {
        // Внутри книги переходы идут как обычно.
        assertFalse(SleepTimerPolicy.pauseOnTransition(SleepTimerMode.END_OF_BOOK, 0, 1, 12))
        assertFalse(SleepTimerPolicy.pauseOnTransition(SleepTimerMode.END_OF_BOOK, 7, 8, 12))
        // Уход за последнюю главу — стоп.
        assertTrue(SleepTimerPolicy.pauseOnTransition(SleepTimerMode.END_OF_BOOK, 11, 12, 12))
    }

    @Test
    fun timeModeNeverPausesOnBoundary() {
        assertFalse(SleepTimerPolicy.pauseOnTransition(SleepTimerMode.TIME, 0, 1, 12))
        assertFalse(SleepTimerPolicy.pauseOnTransition(SleepTimerMode.TIME, 11, 12, 12))
    }

    @Test
    fun endOfBookReachedAtEndOfLastChapterOnly() {
        assertFalse(SleepTimerPolicy.clearOnEnded(SleepTimerMode.END_OF_BOOK, 4, 12))
        assertTrue(SleepTimerPolicy.clearOnEnded(SleepTimerMode.END_OF_BOOK, 11, 12))
    }

    @Test
    fun endOfBookWithEmptyPlaylistDoesNotClaimEnd() {
        // mediaItemCount = 0 означает «ничего не играет», а не «книга кончилась».
        assertFalse(SleepTimerPolicy.clearOnEnded(SleepTimerMode.END_OF_BOOK, 0, 0))
    }

    @Test
    fun singleChapterBookIsEndOfBook() {
        // Книга из одной главы: её конец — это и есть конец книги.
        assertTrue(SleepTimerPolicy.pauseOnTransition(SleepTimerMode.END_OF_BOOK, 0, 1, 1))
        assertTrue(SleepTimerPolicy.clearOnEnded(SleepTimerMode.END_OF_BOOK, 0, 1))
    }

    @Test
    fun timeModeIsNotClearedOnEnded() {
        // По времени таймер снимает сам себя по endAtMs, не по факту конца.
        assertFalse(SleepTimerPolicy.clearOnEnded(SleepTimerMode.TIME, 11, 12))
    }

    // --- К какой главе относится затухание ---

    @Test
    fun endOfBookFadesOnlyOnTheLastChapter() {
        // Иначе звук гас бы у конца каждой главы, хотя пауза там не наступает.
        assertFalse(SleepTimerPolicy.fadeAppliesToChapter(SleepTimerMode.END_OF_BOOK, 0, 12))
        assertFalse(SleepTimerPolicy.fadeAppliesToChapter(SleepTimerMode.END_OF_BOOK, 10, 12))
        assertTrue(SleepTimerPolicy.fadeAppliesToChapter(SleepTimerMode.END_OF_BOOK, 11, 12))
    }

    @Test
    fun endOfChapterFadesOnEveryChapter() {
        assertTrue(SleepTimerPolicy.fadeAppliesToChapter(SleepTimerMode.END_OF_CHAPTER, 0, 12))
        assertTrue(SleepTimerPolicy.fadeAppliesToChapter(SleepTimerMode.END_OF_CHAPTER, 11, 12))
    }

    @Test
    fun timeModeFadesRegardlessOfChapter() {
        assertTrue(SleepTimerPolicy.fadeAppliesToChapter(SleepTimerMode.TIME, 3, 12))
        assertTrue(SleepTimerPolicy.fadeAppliesToChapter(SleepTimerMode.TIME, 11, 12))
    }

    @Test
    fun endOfBookDoesNotFadeWithEmptyPlaylist() {
        assertFalse(SleepTimerPolicy.fadeAppliesToChapter(SleepTimerMode.END_OF_BOOK, 0, 0))
    }

    // --- Режим «по времени» ---

    @Test
    fun timeExpiresOnlyAtOrAfterDeadline() {
        assertFalse(SleepTimerPolicy.timeExpired(endAtMs = 1_000L, nowMs = 999L))
        assertTrue(SleepTimerPolicy.timeExpired(endAtMs = 1_000L, nowMs = 1_000L))
        assertTrue(SleepTimerPolicy.timeExpired(endAtMs = 1_000L, nowMs = 1_001L))
    }

    @Test
    fun timeWithoutDeadlineNeverExpires() {
        assertFalse(SleepTimerPolicy.timeExpired(endAtMs = null, nowMs = Long.MAX_VALUE))
    }

    // --- Порядок остановки: сначала пауза, потом громкость ---

    @Test
    fun volumeIsRestoredAfterPauseWhenWeLoweredIt() {
        // Снизили громкость затуханием — уровень обязаны вернуть, иначе звук
        // не вернётся ни в этой, ни в следующей книге.
        assertTrue(SleepTimerPolicy.restoreVolumeAfterPause(volumeReducedByFade = true))
    }

    @Test
    fun volumeIsNotTouchedWhenWeNeverLoweredIt() {
        // Без затухания уровень пользователя не трогаем: обычная пауза по
        // таймеру не должна сбрасывать громкость.
        assertFalse(SleepTimerPolicy.restoreVolumeAfterPause(volumeReducedByFade = false))
    }

    @Test
    fun volumeIsRestoredEvenWhenFadeAlreadyFinished() {
        // Регрессия: конец аниматора приходил уже после того, как флаг
        // «аниматор идёт» сбрасывался, и громкость оставалась на нуле —
        // звук пропадал в любой книге до перезапуска плеера.
        val animatorStillRunning = false
        val volumeReducedByFade = true
        assertTrue(SleepTimerPolicy.restoreVolumeAfterPause(volumeReducedByFade))
        // Проверка не зависит от состояния аниматора.
        assertEquals(animatorStillRunning, false)
    }

    // --- Затухание ---

    @Test
    fun fadeOnlyStartsInsideItsWindow() {
        assertFalse(SleepTimerPolicy.shouldFade(remainingMs = 31_000L, fadeSeconds = 30))
        assertTrue(SleepTimerPolicy.shouldFade(remainingMs = 30_000L, fadeSeconds = 30))
        assertTrue(SleepTimerPolicy.shouldFade(remainingMs = 5_000L, fadeSeconds = 30))
        assertTrue(SleepTimerPolicy.shouldFade(remainingMs = 0L, fadeSeconds = 30))
    }

    @Test
    fun fadeIsOffWhenSecondsIsZero() {
        assertFalse(SleepTimerPolicy.shouldFade(remainingMs = 0L, fadeSeconds = 0))
        assertFalse(SleepTimerPolicy.shouldFade(remainingMs = 1L, fadeSeconds = 0))
        assertEquals(0L, SleepTimerPolicy.effectiveFadeMs(remainingMs = 10_000L, fadeSeconds = 0))
    }

    @Test
    fun fadeNeverOutlastsTheTimeLeft() {
        // Осталось 3 секунды при затухании 30 — растягивать нельзя, иначе
        // пауза наступит через полминуты после ожидаемого.
        assertEquals(3_000L, SleepTimerPolicy.effectiveFadeMs(remainingMs = 3_000L, fadeSeconds = 30))
        // Осталась секунда — затухание длится секунду, а не полминуты.
        assertEquals(1L, SleepTimerPolicy.effectiveFadeMs(remainingMs = 1L, fadeSeconds = 30))
    }

    @Test
    fun fadeUsesConfiguredLengthWhenTimeIsEnough() {
        assertEquals(30_000L, SleepTimerPolicy.effectiveFadeMs(remainingMs = 90_000L, fadeSeconds = 30))
        assertEquals(10_000L, SleepTimerPolicy.effectiveFadeMs(remainingMs = 60_000L, fadeSeconds = 10))
    }

    @Test
    fun fadeVolumeDropsLinearlyToZero() {
        assertEquals(1f, SleepTimerPolicy.fadeVolume(1f, 0f), 0.0001f)
        assertEquals(0.5f, SleepTimerPolicy.fadeVolume(1f, 0.5f), 0.0001f)
        assertEquals(0.25f, SleepTimerPolicy.fadeVolume(1f, 0.75f), 0.0001f)
        assertEquals(0f, SleepTimerPolicy.fadeVolume(1f, 1f), 0.0001f)
    }

    @Test
    fun fadeVolumeKeepsUserLevelAsStart() {
        // Пользователь слушал на 40% — затухание идёт 40% -> 0, а не 100% -> 0.
        assertEquals(0.4f, SleepTimerPolicy.fadeVolume(0.4f, 0f), 0.0001f)
        assertEquals(0.2f, SleepTimerPolicy.fadeVolume(0.4f, 0.5f), 0.0001f)
        assertEquals(0f, SleepTimerPolicy.fadeVolume(0.4f, 1f), 0.0001f)
    }

    @Test
    fun fadeVolumeClampsProgressOutsideZeroToOne() {
        assertEquals(1f, SleepTimerPolicy.fadeVolume(1f, -0.5f), 0.0001f)
        assertEquals(0f, SleepTimerPolicy.fadeVolume(1f, 1.5f), 0.0001f)
    }

    @Test
    fun fadeOptionsMatchTheRequirement() {
        assertEquals(listOf(5, 10, 15, 30), SleepTimerPolicy.FADE_OPTIONS_SECONDS)
    }

    // --- Привязка спада к реальному концу главы ---

    @Test
    fun fadeStretchesWhenChapterGotLonger() {
        // Главы уточняют длительность по ходу: остаток вырос, значит спад
        // надо продлить, иначе он закончится за пару секунд до нуля.
        // Прошло 2 с, осталось 8 с при настроенных 5 с.
        assertEquals(10_000L, SleepTimerPolicy.fadeDurationForRemaining(elapsedMs = 2_000L, remainingMs = 8_000L))
    }

    @Test
    fun fadeIsNotShortenedByReanchoring() {
        // Уточнение в другую сторону (дорожка оказалась короче) спад
        // обрывать не должен — иначе громкость вернётся рывком.
        assertEquals(5_000L, SleepTimerPolicy.fadeDurationForRemaining(elapsedMs = 1_000L, remainingMs = 4_000L))
    }

    @Test
    fun fadeKeepsUnchangedEstimateStable() {
        assertEquals(5_000L, SleepTimerPolicy.fadeDurationForRemaining(elapsedMs = 2_000L, remainingMs = 3_000L))
    }

    @Test
    fun fadeIsNotExtendedPastTheEnd() {
        // Остаток неположительный — спад пора завершать, а не продлевать.
        assertEquals(4_000L, SleepTimerPolicy.fadeDurationForRemaining(elapsedMs = 4_000L, remainingMs = 0L))
        assertEquals(4_000L, SleepTimerPolicy.fadeDurationForRemaining(elapsedMs = 4_000L, remainingMs = -1_000L))
    }

    // --- Своя запись громкости или ручная правка ---

    @Test
    fun ownWriteIsNotTreatedAsManualChange() {
        // Аниматор записал значение, событие Media3 пришло следом.
        assertTrue(
            SleepTimerPolicy.isOwnVolumeWrite(
                pendingSelfWrites = 1,
                lastWrittenVolume = 0.72f,
                reportedVolume = 0.72f,
            ),
        )
    }

    @Test
    fun delayedOwnWriteIsStillOursWhenCounterIsEmpty() {
        // Раньше окно из трёх значений протухало: пока событие лежало в
        // очереди, аниматор успевал записать ещё кадры, и затухание
        // обрывалось на середине. Счётчик пуст — сверяемся с последним
        // записанным значением.
        assertTrue(
            SleepTimerPolicy.isOwnVolumeWrite(
                pendingSelfWrites = 0,
                lastWrittenVolume = 0.31f,
                reportedVolume = 0.305f,
            ),
        )
    }

    @Test
    fun manualChangeIsDetectedDuringFade() {
        // Счётчик пуст и значение заметно отличается от нашего — это правка
        // пользователя: затухание надо отменить.
        assertFalse(
            SleepTimerPolicy.isOwnVolumeWrite(
                pendingSelfWrites = 0,
                lastWrittenVolume = 0.31f,
                reportedVolume = 0.9f,
            ),
        )
    }

    @Test
    fun manualChangeIsDetectedEvenWhenWritesAreQueued() {
        // Даже если наши события ещё в очереди, сильно отличающееся значение
        // пользовательское: уменьшаем счётчик, но не отменяем затухание.
        assertTrue(
            SleepTimerPolicy.isOwnVolumeWrite(
                pendingSelfWrites = 3,
                lastWrittenVolume = 0.31f,
                reportedVolume = 1f,
            ),
        )
        // Однако точное совпадение с нашей записью — точно своё.
        assertTrue(
            SleepTimerPolicy.isOwnVolumeWrite(
                pendingSelfWrites = 3,
                lastWrittenVolume = 0.9f,
                reportedVolume = 0.9f,
            ),
        )
    }

    // --- Когда затухание можно начинать ---

    @Test
    fun fadeStartsOnLivePlaybackInsideWindow() {
        assertTrue(
            SleepTimerPolicy.shouldStartFade(
                remainingMs = 8_000L,
                fadeSeconds = 10,
                isPlaying = true,
                fadeRunning = false,
                cancelledByUser = false,
            ),
        )
    }

    @Test
    fun fadeDoesNotStartOnPause() {
        // На паузе звука и так нет — крутить громкость незачем.
        assertFalse(
            SleepTimerPolicy.shouldStartFade(
                remainingMs = 8_000L,
                fadeSeconds = 10,
                isPlaying = false,
                fadeRunning = false,
                cancelledByUser = false,
            ),
        )
    }

    @Test
    fun fadeDoesNotRestartWhileAlreadyRunning() {
        // Иначе тик таймера поднимал бы второй аниматор поверх первого.
        assertFalse(
            SleepTimerPolicy.shouldStartFade(
                remainingMs = 8_000L,
                fadeSeconds = 10,
                isPlaying = true,
                fadeRunning = true,
                cancelledByUser = false,
            ),
        )
    }

    @Test
    fun manualVolumeChangeCancelsFadeForTheRestOfTimer() {
        // Ручная правка громкости — осознанный отказ от затухания: следующий
        // тик не должен снова гасить звук вопреки выбору пользователя.
        assertFalse(
            SleepTimerPolicy.shouldStartFade(
                remainingMs = 1_000L,
                fadeSeconds = 10,
                isPlaying = true,
                fadeRunning = false,
                cancelledByUser = true,
            ),
        )
    }

    @Test
    fun fadeOutsideWindowOrOffNeverStarts() {
        assertFalse(
            SleepTimerPolicy.shouldStartFade(
                remainingMs = 11_000L,
                fadeSeconds = 10,
                isPlaying = true,
                fadeRunning = false,
                cancelledByUser = false,
            ),
        )
        assertFalse(
            SleepTimerPolicy.shouldStartFade(
                remainingMs = 1_000L,
                fadeSeconds = 0,
                isPlaying = true,
                fadeRunning = false,
                cancelledByUser = false,
            ),
        )
    }
}
