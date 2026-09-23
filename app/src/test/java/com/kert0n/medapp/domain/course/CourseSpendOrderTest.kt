package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.fixture.FIRST_PLANNED_AT
import com.kert0n.medapp.fixture.FIRST_SCHEDULED_ON
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.availability
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.domain.value.doses
import java.time.LocalTime
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

/** Пачки расходуются сверху вниз и сами не появляются (PLAN D5). */
class CourseSpendOrderTest {

    private val availability = availability(PACK to tablets("20"), OTHER_PACK to tablets("12"))

    /** Пять ближайших доз — столько пунктов сценарий и отдаст на раскладку. */
    private val ahead = 5.doses

    /** Порядок расхода — пачками; тест сравнивает по их номерам, чтобы читаться. */
    private fun order(vararg allocations: Pair<Uuid, Int>) =
        activeCourse(sources = allocations.map { source(it.first, it.second) })
            .spendOrder(ahead, availability).map { it?.id }

    @Test
    fun packsAreSpentTopDown() {
        // Две дозы из первой пачки, дальше вторая: допить начатую и перейти к следующей.
        assertEquals(
            listOf(PACK, PACK, OTHER_PACK, OTHER_PACK, OTHER_PACK),
            order(PACK to 2, OTHER_PACK to 3)
        )
    }

    @Test
    fun reorderingTheMedicineReordersTheSpending() {
        assertEquals(
            listOf(OTHER_PACK, OTHER_PACK, OTHER_PACK, PACK, PACK),
            order(OTHER_PACK to 3, PACK to 2)
        )
    }

    @Test
    fun doseWithoutASuppliedPackIsNotWrittenAtAll() {
        // Выделено три дозы на пять приёмов: две последние получают явный признак
        // необеспеченности, а не «полную дозу неизвестно откуда».
        assertEquals(listOf(PACK, PACK, PACK, null, null), order(PACK to 3))
    }

    @Test
    fun courseWithoutPacksStillPlansItsIntakes() {
        // План без источников порождает пункты, и все они необеспечены (PLAN H1).
        assertEquals(List(5) { null }, order())
    }

    @Test
    fun remainderSmallerThanADoseDoesNotSpillIntoTheNextPack() {
        // По одной таблетке в двух пачках при дозе в две: обеспеченных доз ноль, а не одна.
        val singles = availability(PACK to tablets("1"), OTHER_PACK to tablets("1"))
        val found = activeCourse(sources = listOf(source(PACK, 5), source(OTHER_PACK, 5)))
            .spendOrder(ahead, singles).map { it?.id }
        assertEquals(List(5) { null }, found)
    }

    @Test
    fun packGivesNoMoreThanItPhysicallyHas() {
        // Выделено пять доз, а свободно четыре таблетки — две дозы: дальше идёт вторая пачка.
        val shrunk = availability(PACK to tablets("4"), OTHER_PACK to tablets("12"))
        val found = activeCourse(sources = listOf(source(PACK, 5), source(OTHER_PACK, 5)))
            .spendOrder(ahead, shrunk).map { it?.id }
        assertEquals(listOf(PACK, PACK, OTHER_PACK, OTHER_PACK, OTHER_PACK), found)
    }

    @Test
    fun similarPacksAreNotSubstituted() {
        // Третья пачка того же лекарства лежит рядом и доступна, но в препарат не встаёт сама.
        val elsewhere = Uuid.parse("00000000-0000-4000-8000-000000000023")
        val found = activeCourse(sources = listOf(source(PACK, 2)))
            .spendOrder(ahead, availability(PACK to tablets("20"), elsewhere to tablets("50"))).map { it?.id }
        assertEquals(listOf(PACK, PACK, null, null, null), found)
    }

    @Test
    fun spendingAgreesWithCoverage() {
        // Одно правило, два ответа: сколько доз обеспечено и из чего они возьмутся. Пока это было
        // написано порознь, разойтись они могли молча.
        val course = activeCourse(sources = listOf(source(PACK, 5), source(OTHER_PACK, 4)))
        val remaining = course.remainingOccurrences(CourseProgress.none).toList()
        val covered = course.coverage(CourseProgress.none, availability).coveredDoses
        val supplied = course.spendOrder(remaining.size.doses, availability).count { it != null }
        assertEquals(covered, supplied.doses)
    }

    @Test
    fun scenarioZipsTheOrderWithItsUnansweredItems() {
        // Раскладка по приёмам — работа сценария: какие пункты не отвечены и в каком порядке,
        // знает он, а курс отвечает, из чего они возьмутся.
        val plan: List<CourseIntake> = (0 until 5).map { day ->
            plannedIntake(
                id = Uuid.parse("00000000-0000-4000-8000-00000000007$day"),
                scheduledOn = FIRST_SCHEDULED_ON.plusDays(day.toLong()),
                scheduledTime = LocalTime.of(9, 0),
                plannedAt = FIRST_PLANNED_AT.plusSeconds(86_400L * day)
            )
        }
        val course = activeCourse(sources = listOf(source(PACK, 2)))
        val assigned = plan.zip(course.spendOrder(plan.size.doses, availability)).toMap()
        assertEquals(listOf(PACK, PACK, null, null, null), plan.map { assigned[it]?.id })
    }
}
