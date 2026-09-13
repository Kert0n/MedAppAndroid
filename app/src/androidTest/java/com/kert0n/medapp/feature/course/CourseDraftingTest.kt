package com.kert0n.medapp.feature.course

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Черновик — законное состояние «записал у врача» (PLAN D5): правится названными действиями над
 * прочитанным в той же транзакции, условно по редакции, и ничего не занимает — ни пачек, ни броней.
 */
@RunWith(AndroidJUnit4::class)
class CourseDraftingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var drafting: CourseDrafting

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        drafting = Scenarios(database, LATER).courseDrafting
        // Пачка на общей полке: даже там черновик не ставит серверу ничего.
        database.medKits().upsert(medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity())
        database.packageRepository().add(pack(id = PACK, medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref, quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() = database.close()

    private val prescribed = listOf(
        CourseDrafting.Edit.SetDose(dose("2")),
        CourseDrafting.Edit.SetForm(TABLET_FORM),
        CourseDrafting.Edit.SetSchedule(schedule()),
        CourseDrafting.Edit.SetTotalDoses(Doses(10))
    )

    /** С одной заметкой черновик сохраняется; подключённая пачка им не занята, и серверу не ушло ничего. */
    @Test
    fun aDraftIsSavedFromANoteAndHoldsNoPackage() = runTest {
        val created = drafting.create("Ибупрофен", note = "купить завтра")

        val outcome = drafting.edit(created.id, created.revision, prescribed + CourseDrafting.Edit.Attach(PACK, Doses(5)))

        val saved = (outcome as CourseDrafting.Outcome.Saved).draft
        assertEquals(listOf(PACK), saved.sources.map { it.pkg.id })
        assertNull(database.courseRepository().courseHolding(PACK))
        assertTrue(database.syncOperations().all().isEmpty())
    }

    /** Правки ложатся все или ни одной: отказ домена посреди списка не оставляет половины. */
    @Test
    fun editsGoDownAllOrNone() = runTest {
        val created = drafting.create("Ибупрофен")

        val outcome = drafting.edit(created.id, created.revision, listOf(CourseDrafting.Edit.SetForm(TABLET_FORM), CourseDrafting.Edit.Attach(PACK, Doses(5))))

        assertEquals(CourseDrafting.Outcome.Rejected(CourseRejected.Reason.DOSE_MISSING), outcome)
        assertNull(requireNotNull(database.courseRepository().findDraft(created.id)).form)
    }

    /** Два экрана правят одну редакцию: первая правка ложится, вторая узнаёт, что устарела. */
    @Test
    fun twoEditorsOfOneRevisionOneWins() = runTest {
        val created = drafting.create("Ибупрофен")

        val first = drafting.edit(created.id, created.revision, listOf(CourseDrafting.Edit.SetDose(dose("2"))))
        val second = drafting.edit(created.id, created.revision, listOf(CourseDrafting.Edit.SetDose(dose("3"))))

        assertTrue(first is CourseDrafting.Outcome.Saved)
        assertEquals(CourseDrafting.Outcome.Stale, second)
        assertEquals(dose("2"), database.courseRepository().findDraft(created.id)?.dose)
    }

    /** Пачкой, судьба которой уже решена, не пользуются: подключить её к черновику нельзя (PLAN D3). */
    @Test
    fun aMarkedPackageIsNotAttached() = runTest {
        database.packageRepository().mark(PACK, PackageStatus.REMOVING)
        val created = drafting.create("Ибупрофен")

        val outcome = drafting.edit(created.id, created.revision, prescribed + CourseDrafting.Edit.Attach(PACK, Doses(5)))

        assertEquals(CourseDrafting.Outcome.PackageUnusable, outcome)
    }

    /** Лечение, начатое из черновика, старым экраном черновика не затирается. */
    @Test
    fun aDraftThatBecameTreatmentIsNotWrittenOver() = runTest {
        val created = drafting.create("Ибупрофен")
        val prepared = (drafting.edit(created.id, created.revision, prescribed) as CourseDrafting.Outcome.Saved).draft
        val courses = database.courseRepository()
        courses.activate(requireNotNull(courses.findDraft(created.id)).activate(LATER).getOrThrow())

        val outcome = drafting.edit(created.id, prepared.revision, listOf(CourseDrafting.Edit.Rename("Другое", null)))

        assertEquals(CourseDrafting.Outcome.Gone, outcome)
        assertEquals("Ибупрофен", courses.findRecord(created.id)?.title)
    }

    /** Черновик удаляется целиком; начатое лечение так не удалить. */
    @Test
    fun aDraftIsDiscardedButATreatmentIsNot() = runTest {
        val draft = drafting.create("Черновик")
        val treatment = drafting.create("Лечение")
        val prepared = (drafting.edit(treatment.id, treatment.revision, prescribed) as CourseDrafting.Outcome.Saved).draft
        val courses = database.courseRepository()
        courses.activate(requireNotNull(courses.findDraft(prepared.id)).activate(LATER).getOrThrow())

        assertTrue(drafting.discard(draft.id))
        assertFalse(drafting.discard(treatment.id))
        assertFalse(drafting.discard(Uuid.random()))
        assertNull(courses.findDraft(draft.id))
        assertNotNull(courses.findPlan(treatment.id))
    }
}
