package com.kert0n.medapp.feature.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.FakeSettings
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.courseRepository
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Годность различает коробки-источники и остальные (PLAN D8): источникам с выделением — за три дня
 * и за день системно; всем — в последний день баннером; просроченной и поздно подключённой — только
 * то, что наступило сегодня.
 */
@RunWith(AndroidJUnit4::class)
class ExpiryNoticeTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private lateinit var planning: NotificationPlanning
    private val settings = FakeSettings()
    private val now: Instant = Instant.parse("2027-03-10T06:00:00Z")
    private val today = LocalDate.of(2027, 3, 10)

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        planning = NotificationPlanning(database.intakeRepository(), database.packageRepository(), database.courseRepository(), settings)
    }

    @After
    fun tearDown() = database.close()

    private suspend fun box(id: Uuid, name: String, lastDay: LocalDate) =
        database.packageRepository().add(pack(id = id, name = name, quantity = tablets("20"), form = TABLET_FORM, expiresOn = ExpiryDate(lastDay)))

    private suspend fun treatedFrom(packageId: Uuid) {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = today)),
                CourseDrafting.Edit.SetTotalDoses(Doses(5)),
                CourseDrafting.Edit.Attach(packageId, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
    }

    private fun kindsOf(due: List<com.kert0n.medapp.domain.notification.Reminder>) =
        due.associate { (it.target as NotificationTarget.PackageCard).packageId to (it.kind to it.delivery) }

    @Test
    fun sourcesGetTheAdvanceStagesAndEveryoneGetsTheBannerOnTheLastDay() = runTest {
        val source3 = PACK
        val source1 = OTHER_PACK
        val stranger3 = Uuid.random()
        val lastDayBox = Uuid.random()
        val expired = Uuid.random()
        box(source3, "Источник 3 дня", today.plusDays(3))
        box(source1, "Источник день", today.plusDays(1))
        box(stranger3, "Не источник", today.plusDays(3))
        box(lastDayBox, "Последний день", today)
        box(expired, "Просроченная", today.minusDays(1))
        treatedFrom(source3)
        treatedFrom(source1)

        val due = kindsOf(planning.expiryDue(today, now))

        assertEquals(NotificationKind.EXPIRY_SOURCE_3D to NoticeDelivery.SYSTEM, due[source3])
        assertEquals(NotificationKind.EXPIRY_SOURCE_1D to NoticeDelivery.SYSTEM, due[source1])
        assertEquals(NotificationKind.EXPIRY_TODAY to NoticeDelivery.IN_APP_BANNER, due[lastDayBox])
        // Чужая коробка предварительных этапов не получает; просроченной этапов нет.
        assertEquals(setOf(source3, source1, lastDayBox), due.keys)
    }

    /** Источник, подключённый за день до конца, получает только этап дня — не пачку прошедших. */
    @Test
    fun aLateSourceGetsOnlyTodaysStage() = runTest {
        box(PACK, "Поздний", today.plusDays(1))
        treatedFrom(PACK)
        assertEquals(mapOf(PACK to (NotificationKind.EXPIRY_SOURCE_1D to NoticeDelivery.SYSTEM)), kindsOf(planning.expiryDue(today, now)))
        assertEquals(emptyMap<Uuid, Any>(), kindsOf(planning.expiryDue(today.minusDays(2), now)).filterKeys { it == PACK }.filterValues { it.first != NotificationKind.EXPIRY_SOURCE_3D })
    }

    /** Выключенные напоминания источникам молчат, а баннер последнего дня остаётся. */
    @Test
    fun disabledSourceRemindersKeepTheBanner() = runTest {
        box(PACK, "Источник", today.plusDays(3))
        box(OTHER_PACK, "Сегодня", today)
        treatedFrom(PACK)
        settings.settings = NotificationSettings(expirySourceRemindersEnabled = false)

        assertEquals(mapOf(OTHER_PACK to (NotificationKind.EXPIRY_TODAY to NoticeDelivery.IN_APP_BANNER)), kindsOf(planning.expiryDue(today, now)))
    }
}
