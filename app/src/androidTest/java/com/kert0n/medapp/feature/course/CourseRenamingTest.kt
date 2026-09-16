package com.kert0n.medapp.feature.course

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Опечатка в названии — то же самое лечение (PLAN D5). Переход применяется к записи, прочитанной
 * этой же транзакцией, и правит только названное: назначение, исход и редакция плана его не
 * замечают (F5, C1 «Переход применяет тот, чей это сценарий»).
 */
@RunWith(AndroidJUnit4::class)
class CourseRenamingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() = database.close()

    private suspend fun started(): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    /**
     * Название правится, а назначение остаётся: без этого правила общая запись поверх прочитанного
     * когда-то состояния вернула бы эпизоду прежние дозу и расписание — и редакцию плана вместе с
     * ними (F5).
     */
    @Test
    fun renamingChangesTheNameAndNothingElse() = runTest {
        val id = started()
        val before = requireNotNull(database.courseRepository().findPlan(id))

        assertEquals(CourseRenaming.Outcome.RENAMED, scenarios.courseRenaming.rename(id, "Ибупрофен, неделя", "после еды"))

        val record = requireNotNull(database.courseRepository().findRecord(id))
        assertEquals("Ибупрофен, неделя", record.title)
        assertEquals("после еды", record.note)
        assertTrue(record.isOpen)
        assertEquals(before.prescription, record.prescription)
        assertEquals(before.revision, requireNotNull(database.courseRepository().findPlan(id)).revision)
    }

    /**
     * Пустое имя до базы не доходит. Правило живёт у записи эпизода, и держит его переход: пока
     * его применяло хранение, оно стояло у порта — теперь у того, кто зовёт переход. Порт после
     * этого пишет ровно названное, и других его вызывающих нет.
     */
    @Test
    fun aBlankTitleNeverReachesTheRow() = runTest {
        val id = started()
        val before = requireNotNull(database.courseRepository().findRecord(id)).title

        val refusal = runCatching { scenarios.courseRenaming.rename(id, "   ", null) }.exceptionOrNull()

        assertEquals(IllegalArgumentException::class, requireNotNull(refusal)::class)
        assertEquals(before, requireNotNull(database.courseRepository().findRecord(id)).title)
    }

    /** Записи эпизода нет — править нечего, и сценарий говорит это исходом, а не падением. */
    @Test
    fun renamingWhatIsGoneIsAnOutcome() = runTest {
        assertEquals(CourseRenaming.Outcome.GONE, scenarios.courseRenaming.rename(Uuid.random(), "Что угодно", null))
    }
}
