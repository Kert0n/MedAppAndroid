package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.SourceEditing
import com.kert0n.medapp.feature.notification.DailyRound
import com.kert0n.medapp.feature.notification.NotificationReconciliation
import com.kert0n.medapp.feature.notification.ReminderAnswering
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.FakeServer
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.StoryWorld
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.intakeOn
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.moscow
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.storySetting
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.treatmentStarted
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.platform.time.TimeShifts
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Дмитрия — коробка кончается, а семья тратит** (`docs/истории.md`, U5).
 *
 * Отцовская коробка на исходе, дочь берёт из неё тайком, жена тратит с семейной полки со своего
 * телефона. Нехватку, её починку, сокращение и конец коробки говорит механизм — сверка по
 * изменившимся основаниям и владелец доставки, — а проверка только пересчитывает и нажимает.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SharedBoxStoryTest {

    private val tuesday: LocalDate = LocalDate.of(2027, 3, 23)
    private val wednesday: LocalDate = tuesday.plusDays(1)

    private val world = StoryWorld.begin(moscow(tuesday, 19, 0), MOSCOW)

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var database: MedAppDatabase
    @Inject lateinit var reminders: ReminderStorageRepository
    @Inject lateinit var reconciliation: NotificationReconciliation
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var shifts: TimeShifts
    @Inject lateinit var round: DailyRound
    @Inject lateinit var answering: ReminderAnswering

    private val WAIT = 10_000L
    private val server = FakeServer()
    private val family = medKit(id = SHARED_KIT, name = "Семейная", publication = MedKit.Publication.PUBLISHED, participantCount = 3)

    private val fathers = Uuid.random()
    private val reserve = Uuid.random()
    private val daughters = Uuid.random()
    private lateinit var back: Uuid
    private lateinit var knee: Uuid

    @Before
    fun setUp() {
        hilt.inject()
        world.start(reminders, reconciliation, transactions, shifts, round, answering)
        runBlocking {
            database.storySetting()
            val packages = database.packageRepository()
            // Отцовская початая — на шесть приёмов из двадцати; вторую купил вчера про запас.
            packages.add(pack(id = fathers, name = "Ибупрофен папин", quantity = tablets("6"), form = TABLET_FORM))
            packages.add(pack(id = reserve, name = "Ибупрофен про запас", quantity = tablets("20"), form = TABLET_FORM))
            // Коробка дочери — на семейной полке, общей с женой.
            server.shelf(SHARED_KIT, participants = 3)
            database.medKits().upsert(family.toMedKitStorageEntity())
            server.drug(daughters, SHARED_KIT, name = "Ибупрофен дочки", quantity = "12")
            packages.add(
                pack(id = daughters, name = "Ибупрофен дочки", medKit = family.ref, quantity = tablets("12"), form = TABLET_FORM),
                PackageSyncState(daughters, ResourceVersion(1), ResourceVersion(1), syncedAt = moscow(tuesday, 19, 0))
            )
            back = database.treatmentStarted(moscow(tuesday, 19, 0), "Спина", fathers, tuesday, listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)))
            knee = database.treatmentStarted(moscow(tuesday, 19, 0), "Колено", daughters, tuesday, listOf(LocalTime.of(21, 0)), days = 12)
            server.synchronization(database, world.clock).synchronize()
        }
        compose.setContent {
            MedAppTheme { MedAppShell(opening = world.opening.value, onOpened = { world.opening.value = null }) }
        }
    }

    @After
    fun tearDown() = world.end()

    /**
     * Нехватка вечером; утром пересчёт после дочери — нехватка честнее, прежняя карточка ушла;
     * нажатие ведёт к источникам, подключённый запас гасит нехватку. Днём жена тратит с семейной
     * полки — сокращение сказано, план дочери прежний. Вечером «Принял» из шторки кончает отцовскую
     * коробку прямо на приёме — источником она больше не числится, а история из неё читается.
     *
     * Стережёт: нехватку называет механизм и переназывает после пересчёта; починенная уходит из
     * шторки; чужой расход сокращает обеспечение, а не лечение; коробка кончается на приёме и
     * уносит с собой источник, но не историю.
     */
    @Test
    fun dmitrysBoxRunsOutWhileTheFamilyKeepsSpending() {
        theShortageIsNamedAndNamedAgainAfterTheRecount()
        theNoticeLeadsToTheSourcesAndTheReserveSilencesIt()
        hisWifeSpendsFromTheFamilyShelf()
        theLastTabletEndsTheBoxOnTheIntake()
    }

    private fun theShortageIsNamedAndNamedAgainAfterTheRecount() {
        runBlocking { world.enter() }
        compose.waitUntil(WAIT) { shortageFor(back) != null }
        val evening = requireNotNull(shortageFor(back))

        // Утро среды: дочь взяла четыре. Пересчёт — нехватка с новой датой, прежняя карточка ушла.
        world.moveTo(moscow(wednesday, 7, 0))
        runBlocking {
            world.enter()
            assertEquals(
                PackageAdjusting.Outcome.ADJUSTED,
                Scenarios(database, world.clock.now).packageAdjusting.adjust(fathers, PackageAdjusting.Action.Recount(seen = tablets("6"), actual = tablets("2")))
            )
        }
        compose.waitUntil(WAIT) { shortageFor(back)?.let { it.key != evening.key } == true }
        compose.waitUntil(WAIT) { evening.key !in world.shade.cards }
    }

    /** Нажал на нехватку — источники лечения; подключил запас на остаток — карточка погасла. */
    private fun theNoticeLeadsToTheSourcesAndTheReserveSilencesIt() {
        val shortage = requireNotNull(shortageFor(back))
        runBlocking { world.tap(shortage) }
        compose.waitUntil(WAIT) { shown("Подключить ещё препарат") && shown("Ибупрофен папин") }

        runBlocking {
            val plan = requireNotNull(database.courseRepository().findPlan(back))
            val coverage = requireNotNull(database.courseRepository().observeCoverage(back).first())
            val onFathers = plan.sources.single().allocatedDoses
            val outcome = Scenarios(database, world.clock.now).sourceEditing.save(
                back, plan.revision,
                listOf(
                    SourceEditing.Source(fathers, onFathers),
                    SourceEditing.Source(reserve, Doses(coverage.requiredDoses.count - onFathers.count))
                )
            )
            assertTrue("$outcome", outcome is SourceEditing.Outcome.Saved)
        }
        compose.waitUntil(WAIT) { shown("Ибупрофен про запас") }
        compose.waitUntil(WAIT) { world.shade.cards.none { it.kind in COVERAGE && it.subject.startsWith(back.toString()) } }
    }

    /** Днём жена берёт две из коробки дочери: снимок сокращает обеспечение, план прежний. */
    private fun hisWifeSpendsFromTheFamilyShelf() {
        val planBefore = runBlocking { requireNotNull(database.courseRepository().findPlan(knee)).prescription }
        world.moveTo(moscow(wednesday, 13, 0))
        server.drugs.getValue(daughters).quantity = BigDecimal("10")
        server.drugs.getValue(daughters).version++
        runBlocking { server.synchronization(database, world.clock).synchronize() }

        compose.waitUntil(WAIT) {
            world.shade.shown.any { it.kind == NotificationKind.COVERAGE_SHORT && it.target == NotificationTarget.CourseSources(knee) }
        }
        assertEquals(planBefore, runBlocking { requireNotNull(database.courseRepository().findPlan(knee)).prescription })
    }

    /** Утро и вечер среды — «Принял» из шторки; вторая таблетка кончает отцовскую коробку. */
    private fun theLastTabletEndsTheBoxOnTheIntake() {
        for (hour in listOf(8, 20)) {
            val intake = runBlocking { database.intakeOn(back, wednesday, hour) }
            world.moveTo(moscow(wednesday, hour, 0).plusSeconds(1))
            compose.waitUntil(WAIT) { world.cardUp(intake.id) }
            assertEquals(ReminderAnswering.Response.Done, runBlocking { world.inShade(intake.id, NotificationAction.TAKE) })
            assertEquals(IntakeStatus.TAKEN, runBlocking { database.intakeOn(back, wednesday, hour).status })
        }

        runBlocking {
            assertEquals(null, database.packageRepository().find(fathers)?.takeIf { it.status.allowsUse })
            val sources = requireNotNull(database.courseRepository().findPlan(back)).sources.map { it.pkg.id }
            assertEquals("кончившаяся коробка источником не числится", listOf(reserve), sources)
            val history = database.intakeRepository().observeOfPackage(fathers).first()
            assertEquals("история из кончившейся коробки читается", 2, history.size)
        }
    }

    private fun shortageFor(course: Uuid): Reminder? = world.shade.shown.lastOrNull {
        it.kind in COVERAGE && it.target == NotificationTarget.CourseSources(course) && it.key in world.shade.cards
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        val COVERAGE = setOf(NotificationKind.COVERAGE_3D, NotificationKind.COVERAGE_END)
    }
}
