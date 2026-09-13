package com.kert0n.medapp.storage.database

import com.kert0n.medapp.fixture.confirmed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.reportRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.storage.value.DosageFormStorageEntity
import com.kert0n.medapp.storage.value.QuantityUnitStorageEntity
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Поток проекции переизлучает, когда меняется словарь: проекции держат единицы и формы объектами
 * словаря, и переименование — новость для экрана, хотя ни одна строка пачек, курсов и приёмов не
 * менялась (PLAN H1). Проверяется каждый поток, который собирает единицы и формы по словарю.
 *
 * Красная проверка: убрать таблицы словаря из `observing` — поток молчит, и тест ждёт до таймаута.
 * `runBlocking`, а не `runTest`: база уведомляет из своих потоков, и виртуальное время таймаута
 * истекло бы сразу.
 */
@RunWith(AndroidJUnit4::class)
class ObservationTest {

    private val database: MedAppDatabase = inMemoryDatabase()

    /** 10 марта, 15:00 по Москве. */
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val scenarios = Scenarios(database, now)
    private val today = LocalDate.of(2027, 3, 10)

    @After
    fun tearDown() = database.close()

    /** Следующее значение потока после [change], дождавшись первого — подписка уже стоит. */
    private suspend fun <T> Flow<T>.nextAfter(change: suspend () -> Unit): T = coroutineScope {
        val subscribed = CompletableDeferred<Unit>()
        val next = async { withIndex().onEach { if (it.index == 0) subscribed.complete(Unit) }.drop(1).first().value }
        subscribed.await()
        change()
        withTimeout(10_000) { next.await() }
    }

    private suspend fun renameVocabulary() = database.vocabulary().save(
        units = listOf(QuantityUnitStorageEntity(TABLETS.id, "таб.")),
        forms = listOf(DosageFormStorageEntity(TABLET_FORM.id, "табл."))
    )

    /** Лечение с 10 марта раз в день, 5 доз по две таблетки из [PACK]; первый пункт принят. */
    private suspend fun treated(): Uuid {
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
        val created = scenarios.courseDrafting.create("Парацетамол")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = today)),
                CourseDrafting.Edit.SetTotalDoses(Doses(5)),
                CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        scenarios.courseUpkeep.keepUp()
        val first = database.intakeRepository().ofCourse(draft.id).filterIsInstance<CourseIntake>().minBy { it.plannedAt }
        scenarios.intakeConfirmation.confirm(first.id, PACK, dose("2"), now).confirmed()
        return draft.id
    }

    @Test
    fun everyProjectionStreamFollowsTheVocabulary() = runBlocking {
        val id = treated()
        val packages = database.packageRepository()
        val courses = database.courseRepository()
        val reports = database.reportRepository()

        assertEquals("таб.", packages.observe(PACK).nextAfter(::renameVocabulary)?.quantity?.unit?.name)
        database.vocabulary().save(listOf(QuantityUnitStorageEntity(TABLETS.id, "таблетка")), listOf(DosageFormStorageEntity(TABLET_FORM.id, "таблетки")))

        val checks: List<Pair<String, suspend () -> String?>> = listOf(
            "план курса" to { courses.observePlan(id).nextAfter(::renameVocabulary)?.prescription?.dose?.unit?.name },
            "записи эпизодов" to { courses.observeRecords().nextAfter(::renameVocabulary).single().prescription.form.name },
            "запись эпизода" to { courses.observeRecord(id).nextAfter(::renameVocabulary)?.prescription?.dose?.unit?.name },
            "пункты курса" to { database.intakeRepository().observeOfCourse(id).nextAfter(::renameVocabulary).first().unit.name },
            "истраченное" to {
                reports.observeSpending(SpendingPeriod(today, today), MOSCOW).nextAfter(::renameVocabulary).episodes.single().total.unit.name
            },
            "расход на дату" to {
                reports.observeFutureSpending(SpendingHorizon(today, today.plusDays(3))).nextAfter(::renameVocabulary).episodes.single().total.unit.name
            },
            "сводка" to { reports.observeStockSummary().nextAfter(::renameVocabulary).byForm.single().form?.name },
            "план на дату" to {
                (reports.observeDayPlan(today, MOSCOW).nextAfter(::renameVocabulary).items.first() as DayPlan.Item.Scheduled).intake.unit.name
            }
        )
        for ((name, check) in checks) {
            val expected = if (name == "записи эпизодов" || name == "сводка") "табл." else "таб."
            assertEquals(name, expected, check())
            database.vocabulary().save(listOf(QuantityUnitStorageEntity(TABLETS.id, "таблетка")), listOf(DosageFormStorageEntity(TABLET_FORM.id, "таблетки")))
        }
    }
}
