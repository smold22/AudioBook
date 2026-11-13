package com.yourapp.audiobook.player

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlin.math.abs

/**
 * Чистые правила таймера сна: когда ставить паузу и как считать затухание.
 *
 * Вынесено из [PlayerController] без зависимости от ExoPlayer, чтобы
 * логику можно было покрыть обычными unit-тестами.
 */
object SleepTimerPolicy {

    /** Длительность затухания по умолчанию: выключено. */
    const val FADE_DEFAULT_SECONDS = 0

    /** Доступные в настройках длительности затухания, секунды. */
    val FADE_OPTIONS_SECONDS = listOf(5, 10, 15, 30)

    /** Допуск при сравнении громкости: float, плюс округление плеера. */
    const val VOLUME_EPSILON = 0.01f

    /**
     * Пауза ли нужна при автоматическом переходе [fromIndex] -> [toIndex].
     *
     * «До конца главы» срабатывает на любой границе глав. «До конца книги» —
     * только если переход ушёл за последнюю главу, поэтому внутри книги он
     * не мешает. Обычно ExoPlayer на этом просто доигрывает последний трек до
     * STATE_ENDED, но лишний переход (повтор, смена очереди) ловим здесь.
     */
    fun pauseOnTransition(
        mode: SleepTimerMode,
        fromIndex: Int,
        toIndex: Int,
        mediaItemCount: Int,
    ): Boolean = when (mode) {
        SleepTimerMode.TIME -> false
        SleepTimerMode.END_OF_CHAPTER -> true
        SleepTimerMode.END_OF_BOOK -> toIndex >= mediaItemCount
    }

    /**
     * Применимо ли затухание к текущей главе.
     *
     * Для режимов «до конца главы» и «по времени» — всегда. Для «до конца
     * книги» — только на последней главе: иначе звук гас бы у конца каждой
     * главы, хотя пауза там не наступает.
     */
    fun fadeAppliesToChapter(
        mode: SleepTimerMode,
        currentIndex: Int,
        mediaItemCount: Int,
    ): Boolean = when (mode) {
        SleepTimerMode.TIME -> true
        SleepTimerMode.END_OF_CHAPTER -> true
        SleepTimerMode.END_OF_BOOK -> mediaItemCount > 0 && currentIndex >= mediaItemCount - 1
    }

    /**
     * Пауза ли нужна, когда воспроизведение дошло до конца [index] из [mediaItemCount].
     *
     * Состояние STATE_ENDED приходит уже после паузы, поэтому здесь только
     * снимаем таймер: «до конца главы» к этому моменту сработало на переходе,
     * а «до конца книги» — на последней главе.
     */
    fun clearOnEnded(mode: SleepTimerMode, index: Int, mediaItemCount: Int): Boolean = when (mode) {
        SleepTimerMode.TIME -> false
        SleepTimerMode.END_OF_CHAPTER -> true
        SleepTimerMode.END_OF_BOOK -> index >= mediaItemCount - 1 && mediaItemCount > 0
    }

    /** Истёк ли срок таймера «по времени». */
    fun timeExpired(endAtMs: Long?, nowMs: Long): Boolean = endAtMs != null && nowMs >= endAtMs

    /**
     * Возвращать ли громкость после паузы.
     *
     * Возвращать нужно по факту «мы снизили громкость и ещё не вернули», а не
     * по факту «аниматор сейчас идёт»: восстановление происходит уже после
     * снятия аниматора, и при проверке по аниматору уровень оставался бы на
     * нуле — звук пропадал бы до следующей книги.
     *
     * Возвращать тоже после паузы: на границе главы ExoPlayer успевает отдать
     * первые кадры следующей главы, и возврат до паузы звучал бы прыжком.
     */
    fun restoreVolumeAfterPause(volumeReducedByFade: Boolean): Boolean = volumeReducedByFade

    /**
     * Осталось ли времени до остановки меньше, чем длина затухания.
     *
     * [remainingMs] — оценка остатка: для «по времени» это остаток до [endAtMs],
     * для режимов по главе/книге — остаток текущей главы.
     */
    fun shouldFade(remainingMs: Long, fadeSeconds: Int): Boolean =
        fadeSeconds > 0 && remainingMs in 0..(fadeSeconds * 1000L)

    /**
     * Должно ли затухание продолжаться прямо сейчас.
     *
     * Снимается на паузе, после ручной правки громкости и когда затухание
     * уже идёт: иначе тик таймера тут же поднял бы новый аниматор поверх
     * только что отменённого.
     */
    fun shouldStartFade(
        remainingMs: Long,
        fadeSeconds: Int,
        isPlaying: Boolean,
        fadeRunning: Boolean,
        cancelledByUser: Boolean,
    ): Boolean = isPlaying && !fadeRunning && !cancelledByUser && shouldFade(remainingMs, fadeSeconds)

    /**
     * Длительность спада, при которой он закончится ровно на [remainingMs].
     *
     * Уже прошло [elapsedMs] миллисекунд анимации, а до конца главы осталось
     * [remainingMs]. Длительность главы уточняется по ходу воспроизведения
     * (VBR-дорожка, стриминг, дозагрузка), поэтому оценка остатка на старте
     * затухания устаревает: разовый фиксированный `ValueAnimator` заканчивался
     * на пару секунд раньше конца главы.
     *
     * Возвращаем `elapsedMs + remainingMs` — аниматор дотянется ровно до нуля.
     * Если остаток неположительный, спад пора завершать, а не продлевать.
     */
    fun fadeDurationForRemaining(elapsedMs: Long, remainingMs: Long): Long {
        if (remainingMs <= 0L) return elapsedMs.coerceAtLeast(0L)
        return elapsedMs.coerceAtLeast(0L) + remainingMs
    }

    /**
     * Длительность затухания с учётом того, сколько реально осталось.
     *
     * Если до конца 3 секунды, а выбрано 30, растягивать нельзя — иначе пауза
     * наступит через полминуты. Затухание сжимается до остатка.
     */
    fun effectiveFadeMs(remainingMs: Long, fadeSeconds: Int): Long {
        if (fadeSeconds <= 0) return 0L
        val configured = fadeSeconds * 1000L
        return if (remainingMs in 1 until configured) remainingMs else configured
    }

    /**
     * Своя это запись громкости или пользовательская правка.
     *
     * Media3 присылает [Player.Listener.onVolumeChanged] не сразу, а на
     * следующем кадре главного потока: пока событие лежит в очереди, аниматор
     * успевает записать ещё несколько значений. Поэтому историю «последних N»
     * использовать нельзя — она успевает протухнуть, и собственная запись
     * аниматора принимается за ручную, обрывая затухание на середине.
     *
     * Вместо этого считаем записи 1:1 (каждая запись в плеер присылает ровно
     * одно событие [Player.Listener.onVolumeChanged]) и сверяемся с последним
     * записанным значением — на случай, если Media3 всё же склеит события.
     *
     * Возвращает true, если это собственная запись; тогда [pendingSelfWrites]
     * нужно уменьшить на единицу.
     */
    fun isOwnVolumeWrite(
        pendingSelfWrites: Int,
        lastWrittenVolume: Float,
        reportedVolume: Float,
        epsilon: Float = VOLUME_EPSILON,
    ): Boolean = pendingSelfWrites > 0 ||
        abs(reportedVolume - lastWrittenVolume) <= epsilon

    /**
     * Громкость в момент затухания.
     *
     * Линейный спад от [startVolume] к нулю на [progress] ∈ [0, 1].
     * Возвращает [startVolume] при progress ≤ 0 и 0 при progress ≥ 1.
     */
    fun fadeVolume(startVolume: Float, progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return startVolume * (1f - clamped)
    }
}
