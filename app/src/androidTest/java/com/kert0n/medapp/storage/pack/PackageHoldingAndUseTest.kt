package com.kert0n.medapp.storage.pack

import com.kert0n.medapp.fixture.confirmed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Две вещи о коробке, которые знает не она: какой идущий курс её держит и когда из неё брали
 * последний раз (PLAN D4, экраны 6 и 17).
 */
@RunWith(AndroidJUnit4::class)
class PackageHoldingAndUseTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
        database.packageRepository().add(pack(id = OTHER_PACK, name = "Парацетамол", quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() = database.close()

    private suspend fun projected(id: Uuid): PackageProjection =
        requireNotNull(database.packageRepository().observe(id).first())

    /** Черновик с [PACK] в составе; вернуть — его номер и редакцию. */
    private suspend fun drafted(): CourseDrafting.Outcome.Saved {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        return scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(5)),
                CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved
    }

    /** Черновик коробку не занимает; начатое лечение — занимает; отменённое — отпускает. */
    @Test
    fun onlyAStartedTreatmentHoldsThePackage() = runTest {
        val draft = drafted().draft
        assertNull(projected(PACK).holdingCourseId)

        scenarios.courseActivation.activate(draft.id, draft.revision)
        assertEquals(draft.id, projected(PACK).holdingCourseId)
        assertNull(projected(OTHER_PACK).holdingCourseId)

        scenarios.courseCancellation.cancel(draft.id)
        assertNull(projected(PACK).holdingCourseId)
    }

    /**
     * Последний приём — самый поздний из разового и курсового; записанный задним числом назад
     * его не сдвигает; коробка, из которой не брали, — без него.
     */
    @Test
    fun lastUseIsTheLatestOfMyIntakesFromTheBox() = runTest {
        assertNull(projected(PACK).lastUsedAt)
        val draft = drafted().draft
        scenarios.courseActivation.activate(draft.id, draft.revision)

        val earlier = now.minusSeconds(3_600)
        scenarios.unplannedIntakeRecording.record(PACK, dose("1"), earlier)
        assertEquals(earlier, projected(PACK).lastUsedAt)

        val first = database.intakeRepository().ofCourse(draft.id).filterIsInstance<CourseIntake>().minBy { it.plannedAt }
        scenarios.intakeConfirmation.confirm(first.id, PACK, dose("2"), now).confirmed()
        assertEquals(now, projected(PACK).lastUsedAt)

        scenarios.unplannedIntakeRecording.record(PACK, dose("1"), now.minusSeconds(86_400))
        assertEquals(now, projected(PACK).lastUsedAt)
        assertNull(projected(OTHER_PACK).lastUsedAt)
    }
}
