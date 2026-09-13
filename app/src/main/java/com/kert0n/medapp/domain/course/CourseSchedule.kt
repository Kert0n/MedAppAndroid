package com.kert0n.medapp.domain.course

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Objects

/**
 * Календарное намерение человека: с какого числа, в какие дни недели, в какое время и в какой
 * зоне. Конца у него нет: курс — это N доз с даты, а не окно дат, и календарь говорит, **по
 * каким дням и во сколько**, а не до какого числа — дата окончания следствие, и её сдвигает
 * каждый пропуск (PLAN D5). Величина: другой набор времён — другое расписание, и у действующего
 * курса его меняет изменение лечения, а не правка на месте. «В девять утра» — девять утра своей зоны, а не системной, поэтому
 * перелёт лечение не сдвигает.
 *
 * Точность — минута: секунды в назначении не значат ничего, и допускать их здесь значило бы
 * называть разными расписания, которые описывают один и тот же приём.
 */
class CourseSchedule(
    val start: LocalDate,
    daysOfWeek: Set<DayOfWeek>,
    times: List<LocalTime>,
    val zone: ZoneId
) {

    /**
     * Свои копии, а не переданные коллекции: `val` защищает ссылку, а не содержимое, и список,
     * оставшийся у вызывающего, менял бы расписание действующего курса — вместе с назначением в
     * записи эпизода — мимо изменения лечения (PLAN D5).
     */
    val daysOfWeek: Set<DayOfWeek> = daysOfWeek.toSet()

    val times: List<LocalTime> = times.toList()

    init {
        // Пустая маска дней — расписание без приёмов, а не «каждый день».
        require(daysOfWeek.isNotEmpty()) { "расписание без дней недели не порождает приёмов" }
        require(times.isNotEmpty()) { "расписание без времён не порождает приёмов" }
        require(times.distinct().size == times.size) {
            "одно и то же время дважды — это один приём, а не два"
        }
        require(times == times.sorted()) { "времена хранятся по возрастанию" }
        // Лекарство принимают в 09:00, а не в 09:00:10: точность расписания — минута, и это
        // правило самого расписания. Иначе два времени, различных здесь, стали бы одним ниже.
        require(times.all { it.second == 0 && it.nano == 0 }) {
            "время приёма называется с точностью до минуты: $times"
        }
    }

    /** Момент, с которого календарь читается с самого начала: полночь первого дня в своей зоне. */
    val beginning: Instant get() = start.atStartOfDay(zone).toInstant()

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is CourseSchedule &&
                start == other.start &&
                daysOfWeek == other.daysOfWeek &&
                times == other.times &&
                zone == other.zone
            )

    override fun hashCode(): Int = Objects.hash(start, daysOfWeek, times, zone)

    override fun toString(): String = "CourseSchedule(с $start, $daysOfWeek, $times, $zone)"

    /**
     * Пункты, чей момент попадает в `[from, until)`, по возрастанию момента. Соседние окна
     * стыкуются без повтора и без дыры; длину окна выбирает вызывающий (PLAN F4). Конца у
     * календаря нет: сколько пунктов ещё нужно, знает курс, и окно он режет сам.
     */
    fun occurrences(from: Instant, until: Instant): List<ScheduledOccurrence> {
        require(!until.isBefore(from)) { "интервал [from, until) не бывает обратным" }
        if (until == from) return emptyList()
        // Сутки запаса с каждой стороны: момент зависит от перехода часов, поэтому отбор идёт по
        // моменту, а не по дате. `atZone().toLocalDate()` — потому что `LocalDate.ofInstant`
        // появился только в API 34.
        val firstDate = maxOf(start, from.atZone(zone).toLocalDate().minusDays(1))
        val lastDate = until.atZone(zone).toLocalDate()
        if (lastDate.isBefore(firstDate)) return emptyList()

        val found = ArrayList<ScheduledOccurrence>()
        var date = firstDate
        while (!date.isAfter(lastDate)) {
            if (date.dayOfWeek in daysOfWeek) {
                for (time in times) {
                    val at = momentOf(date, time)
                    if (!at.isBefore(from) && at.isBefore(until)) {
                        found += ScheduledOccurrence(date, time, at)
                    }
                }
            }
            date = date.plusDays(1)
        }
        return found.sortedWith(
            compareBy<ScheduledOccurrence> { it.at }.thenBy { it.localDate }.thenBy { it.localTime }
        )
    }

    /**
     * Ближайшие [count] пунктов, начиная с [from], по возрастанию момента, минуя [except] — уже
     * отвеченные. Это и есть «когда» оставшихся доз: сколько их, говорит курс, а календарь
     * раскладывает их по дням, пропуская занятые места. Последний из них — ожидаемый конец
     * лечения: пропуск сдвигает его вперёд, поздний ответ по пропущенному — назад. Отвеченный
     * пункт узнаётся по назначенным дате и времени — это его тождество (PLAN F4).
     */
    fun next(from: Instant, count: Int, except: Set<ScheduledOccurrence> = emptySet()): List<ScheduledOccurrence> {
        require(count >= 0) { "число пунктов не бывает отрицательным: $count" }
        if (count == 0) return emptyList()
        val taken = except.mapTo(HashSet()) { it.slot }
        val found = ArrayList<ScheduledOccurrence>(count)
        // Сутки запаса назад: момент зависит от перехода часов, отбор идёт по моменту.
        var date = maxOf(start, from.atZone(zone).toLocalDate().minusDays(1))
        while (found.size < count) {
            if (date.dayOfWeek in daysOfWeek) {
                for (time in times) {
                    if ((date to time) in taken) continue
                    val at = momentOf(date, time)
                    if (!at.isBefore(from)) found += ScheduledOccurrence(date, time, at)
                }
            }
            date = date.plusDays(1)
        }
        return found
            .sortedWith(compareBy<ScheduledOccurrence> { it.at }.thenBy { it.localDate }.thenBy { it.localTime })
            .take(count)
    }

    /**
     * Момент назначенного времени в зоне расписания. Несуществующее время (перевод вперёд)
     * сдвигается к моменту перевода; время, которое бывает дважды (перевод назад), берётся
     * первым. Правило записано явно, а не доверено `ZonedDateTime.of`, потому что сдвиг приёма
     * на другой час человек заметит.
     */
    private fun momentOf(date: LocalDate, time: LocalTime): Instant {
        val local = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(local)
        return when {
            offsets.isEmpty() -> zone.rules.getTransition(local).instant
            else -> local.toInstant(offsets.first())
        }
    }
}
