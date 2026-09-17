package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.domain.report.Spending
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.domain.course.CourseRecord
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Истраченное — в состояние экрана: две полки, группы по единицам, доли внутри группы. */
class SpendingMapperTest {

    private val today = LocalDate.of(2027, 3, 10)
    private val period = SpendingPeriod(LocalDate.of(2027, 2, 10), today)

    private fun episode(title: String, total: Quantity, intakes: Int, id: Uuid = COURSE, outcome: CourseRecord.Outcome? = null) =
        Spending.Episode(courseRecord(id = id, title = title, outcome = outcome, closedAt = outcome?.let { Instant.parse("2027-03-01T10:00:00Z") }).projection(), total, intakes)

    private fun box(name: String, total: Quantity, intakes: Int) =
        Spending.Box(Uuid.random(), name, total, intakes)

    private fun spending(episodes: List<Spending.Episode> = emptyList(), boxes: List<Spending.Box> = emptyList()) =
        Spending(episodes, boxes).toPresentationDTO(period, today, PeriodPreset.MONTH)

    /** Отчёт называет свой срок и сегодняшний день: по ним рисуются заголовок и календарь. */
    @Test
    fun theReportNamesItsPeriod() {
        val dto = spending(listOf(episode("Парацетамол", tablets("10"), 5)))

        assertEquals(LocalDate.of(2027, 2, 10), dto.from)
        assertEquals(today, dto.to)
        assertEquals(today, dto.today)
        assertEquals(PeriodPreset.MONTH, dto.preset)
    }

    /**
     * **Закончившееся лечение из отчёта не пропадает:** запись эпизода живёт вечно, и приёмы,
     * сделанные по нему, истрачены так же, как по идущему (PLAN H6).
     */
    @Test
    fun aFinishedTreatmentStaysInTheReport() {
        val dto = spending(
            listOf(
                episode("Парацетамол", tablets("10"), 5, outcome = CourseRecord.Outcome.COMPLETED),
                episode("Ибупрофен", tablets("4"), 2, id = Uuid.random())
            )
        )

        assertEquals(listOf("Парацетамол", "Ибупрофен"), dto.episodes.single().rows.map { it.title })
    }

    /** Лечение и разовые приёмы — разные полки: за ними стоят разные вещи. */
    @Test
    fun treatmentsAndOneOffIntakesLieOnTheirOwnShelves() {
        val dto = spending(
            episodes = listOf(episode("Парацетамол", tablets("10"), 5)),
            boxes = listOf(box("Цетрин", tablets("3"), 3))
        )

        assertEquals(listOf("Парацетамол"), dto.episodes.single().rows.map { it.title })
        assertEquals(listOf("Цетрин"), dto.boxes.single().rows.map { it.name })
    }

    /** Разные единицы — разные группы; складывает их сам домен и только внутри единицы. */
    @Test
    fun unitsGetTheirOwnGroups() {
        val dto = spending(
            listOf(
                episode("Парацетамол", tablets("10"), 5),
                episode("Ибупрофен", tablets("2"), 1, id = Uuid.random()),
                episode("Амброксол", millilitres("30"), 3, id = Uuid.random())
            )
        )

        assertEquals(listOf("12", "30"), dto.episodes.map { it.total.amount })
        // Доля округляется до шести знаков: полоса в пикселях и не заметила бы большего.
        assertEquals(10f / 12, dto.episodes.first().rows.first().share, 0.00001f)
        assertEquals(2f / 12, dto.episodes.first().rows.last().share, 0.00001f)
        assertEquals(1f, dto.episodes.last().rows.single().share, 0.00001f)
    }

    /** Числа нормализованы: `10` и `10.000000` дают одинаковую строку. */
    @Test
    fun amountsAreNormalised() {
        val dto = spending(listOf(episode("Парацетамол", tablets("10.000000"), 5)))

        assertEquals("10", dto.episodes.single().rows.single().amount.amount)
        assertEquals(5, dto.episodes.single().rows.single().intakes)
    }

    /** Две одноимённые коробки остаются двумя строками: каждая пачка учитывается отдельно (C0). */
    @Test
    fun twoBoxesOfTheSameNameStayTwoRows() {
        val dto = spending(boxes = listOf(box("Цетрин", tablets("3"), 3), box("Цетрин", tablets("1"), 1)))

        assertEquals(2, dto.boxes.single().rows.size)
    }

    /** Приёмов не было — отчёт пуст, а не полон нулей. */
    @Test
    fun anEmptyPeriodGivesAnEmptyReport() {
        val dto = Spending.EMPTY.toPresentationDTO(period, today, PeriodPreset.YEAR)

        assertTrue(dto.isEmpty)
    }
}
