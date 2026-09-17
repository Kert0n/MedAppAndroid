package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.domain.report.FutureSpending
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.tablets
import java.time.LocalDate
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Будущий расход — в состояние экрана: полки по единицам, дозы и количество врозь (PLAN H3). */
class FutureSpendingMapperTest {

    private val today = LocalDate.of(2027, 3, 3)
    private val horizon = SpendingHorizon(today, LocalDate.of(2027, 4, 3))

    private fun episode(title: String, doses: Int, total: com.kert0n.medapp.domain.value.Quantity, id: Uuid = COURSE) =
        FutureSpending.Episode(courseRecord(id = id, title = title).projection(), Doses(doses), total)

    /** Оба конца срока в отчёте: заголовок говорит «до какого дня», календарь считает от «сегодня». */
    @Test
    fun theReportNamesBothEndsOfItsPeriod() {
        val dto = FutureSpending(listOf(episode("Парацетамол", 3, tablets("6")))).toPresentationDTO(horizon, HorizonPreset.MONTH)

        assertEquals(today, dto.today)
        assertEquals(LocalDate.of(2027, 4, 3), dto.until)
        assertEquals(HorizonPreset.MONTH, dto.preset)
    }

    /** Свою дату отчёт не выдаёт за пресет: отмеченного чипа у неё нет. */
    @Test
    fun anOwnDateIsNotAPreset() {
        val dto = FutureSpending(listOf(episode("Парацетамол", 3, tablets("6")))).toPresentationDTO(horizon, null)

        assertEquals(null, dto.preset)
    }

    /**
     * Разные единицы — разные полки, и сумма считается внутри полки: таблетки с миллилитрами не
     * складываются никогда (PLAN H6).
     */
    @Test
    fun unitsGetTheirOwnShelves() {
        val dto = FutureSpending(
            listOf(
                episode("Парацетамол", 3, tablets("6")),
                episode("Амброксол", 5, millilitres("50"), Uuid.random()),
                episode("Ибупрофен", 1, tablets("2"), Uuid.random())
            )
        ).toPresentationDTO(horizon, HorizonPreset.MONTH)

        assertEquals(listOf("таблетка", "мл"), dto.groups.map { it.total.unit.name })
        assertEquals("8", dto.groups.first().total.amount)
        assertEquals("50", dto.groups.last().total.amount)
    }

    /** Доля строки считается внутри своей полки: шесть таблеток из восьми — три четверти. */
    @Test
    fun theShareOfARowIsCountedInsideItsShelf() {
        val dto = FutureSpending(
            listOf(episode("Парацетамол", 3, tablets("6")), episode("Ибупрофен", 1, tablets("2"), Uuid.random()))
        ).toPresentationDTO(horizon, HorizonPreset.MONTH)

        assertEquals(listOf(0.75f, 0.25f), dto.groups.single().rows.map { it.share })
    }

    /** Дозы и количество — разные числа: «сколько раз» и «сколько покупать» спрашивают порознь. */
    @Test
    fun dosesAndAmountStayApart() {
        val dto = FutureSpending(listOf(episode("Парацетамол", 3, tablets("6.000000"))))
            .toPresentationDTO(horizon, HorizonPreset.WEEK)

        val row = dto.groups.single().rows.single()
        assertEquals(3, row.doses)
        assertEquals("6", row.amount.amount)
        assertEquals(COURSE, row.courseId)
    }

    /** Лечений на срок нет — отчёт пуст, а не полон нулей. */
    @Test
    fun nothingAheadGivesAnEmptyReport() {
        val dto = FutureSpending.EMPTY.toPresentationDTO(horizon, HorizonPreset.WEEK)

        assertTrue(dto.isEmpty)
        assertEquals(emptyList<ReportGroupPresentationDTO<FutureRowPresentationDTO>>(), dto.groups)
    }
}
