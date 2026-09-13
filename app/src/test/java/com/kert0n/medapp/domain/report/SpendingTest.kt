package com.kert0n.medapp.domain.report

import com.kert0n.medapp.domain.intake.TakenDose
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.OTHER_INTAKE
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.unplannedIntake
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Истраченное — сумма моих приёмов: курсовые строкой эпизода, разовые строкой коробки; одинаковые
 * названия не объединяются, разные единицы не складываются (PLAN H6, C0).
 */
class SpendingTest {

    private val record = courseRecord().projection()
    private val laterRecord = courseRecord(id = OTHER_COURSE, title = "Ибупрофен", startedAt = LATER).projection()

    private fun takenByCourse(id: Uuid, amount: String, courseId: Uuid = COURSE) =
        plannedIntake(id = id, courseId = courseId).confirm(TakenDose(pack(id = PACK).ref, dose(amount), LATER))

    @Test
    fun courseIntakesOfOneEpisodeAreOneRow() {
        val spending = Spending.of(listOf(takenByCourse(INTAKE, "2"), takenByCourse(OTHER_INTAKE, "3")), mapOf(COURSE to record))

        assertEquals(listOf(Spending.Episode(record, tablets("5"), 2)), spending.episodes)
        assertTrue("курсовой приём строки коробки не даёт", spending.packages.isEmpty())
    }

    /** Две коробки с одним названием — две вещи (C0), и строк две. */
    @Test
    fun oneOffIntakesFromTwoBoxesOfOneNameAreTwoRows() {
        val spending = Spending.of(
            listOf(
                unplannedIntake(id = INTAKE, taken = pack(id = PACK), takenAmount = dose("1")),
                unplannedIntake(id = OTHER_INTAKE, taken = pack(id = OTHER_PACK), takenAmount = dose("2"))
            ),
            emptyMap()
        )

        assertEquals(
            listOf(
                Spending.Box(PACK, "Парацетамол", tablets("1"), 1),
                Spending.Box(OTHER_PACK, "Парацетамол", tablets("2"), 1)
            ),
            spending.packages
        )
    }

    /**
     * Лечение, чью единицу сменили, даёт строку на каждую единицу.
     *
     * Красная проверка: сложить по эпизоду — `Quantity.plus` откажет таблеткам с миллилитрами.
     */
    @Test
    fun anEpisodeInTwoUnitsIsTwoRowsNotAFailure() {
        val tabletIntake = takenByCourse(INTAKE, "2")
        val liquidIntake = plannedIntake(id = OTHER_INTAKE, plannedAmount = dose(millilitres("5")))
            .confirm(TakenDose(pack(id = OTHER_PACK, quantity = millilitres("100")).ref, dose(millilitres("5")), LATER))

        val spending = Spending.of(listOf(tabletIntake, liquidIntake), mapOf(COURSE to record))

        assertEquals(setOf(tablets("2"), millilitres("5")), spending.episodes.map { it.total }.toSet())
    }

    @Test
    fun episodesGoFromTheLatestStartedAndBoxesByName() {
        val spending = Spending.of(
            listOf(
                takenByCourse(INTAKE, "2"),
                takenByCourse(OTHER_INTAKE, "1", courseId = OTHER_COURSE),
                unplannedIntake(id = Uuid.random(), taken = pack(id = PACK, name = "Цитрамон")),
                unplannedIntake(id = Uuid.random(), taken = pack(id = OTHER_PACK, name = "Аспирин"))
            ),
            mapOf(COURSE to record, OTHER_COURSE to laterRecord)
        )

        assertEquals(listOf(OTHER_COURSE, COURSE), spending.episodes.map { it.record.id })
        assertEquals(listOf("Аспирин", "Цитрамон"), spending.packages.map { it.name })
    }

    @Test
    fun noIntakesIsAnEmptyReport() {
        assertEquals(Spending.EMPTY, Spending.of(emptyList(), emptyMap()))
        assertTrue(Spending.EMPTY.isEmpty)
    }

    private companion object {
        val OTHER_COURSE: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000052")
    }
}
