package com.kert0n.medapp.app.navigation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.connectivity.Connection
import com.kert0n.medapp.feature.course.SourceEditing
import com.kert0n.medapp.feature.notification.DailyRound
import com.kert0n.medapp.feature.notification.NotificationReconciliation
import com.kert0n.medapp.feature.notification.ReminderAnswering
import com.kert0n.medapp.fixture.FakeServer
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.StoryWorld
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.moscow
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.storySetting
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.treatmentStarted
import com.kert0n.medapp.platform.time.TimeShifts
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.queue.pack.PackageSyncState
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Нины — початая должна уйти первой** (`docs/истории.md`).
 *
 * Нина ставит очередь источников пальцем, и дальше в её лечение вмешивается не она: муж тратит с
 * общей полки, коробка уходит с полки совсем. Очередь — это из чего возьмут следующую дозу, и
 * снимок сервера переставлять её не вправе.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SourceQueueStoryTest {

    private val monday: LocalDate = LocalDate.of(2027, 4, 5)

    private val world = StoryWorld.begin(moscow(monday, 9, 0), MOSCOW)

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var database: MedAppDatabase
    @Inject lateinit var connection: Connection
    @Inject lateinit var reminders: ReminderStorageRepository
    @Inject lateinit var reconciliation: NotificationReconciliation
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var shifts: TimeShifts
    @Inject lateinit var round: DailyRound
    @Inject lateinit var answering: ReminderAnswering

    private val WAIT = 10_000L
    private val server = FakeServer()
    private val family = medKit(id = SHARED_KIT, name = "Семейная", publication = MedKit.Publication.PUBLISHED, participantCount = 2)

    /** Початая — срок кончается в этом месяце; её и надо допить. */
    private val opened = Uuid.random()

    /** Коробка мужа на той же полке. */
    private val his = Uuid.random()

    /** Новая, купленная про запас: дома, срок далёкий. */
    private val spare = Uuid.random()

    private lateinit var back: Uuid

    @Before
    fun setUp() {
        hilt.inject()
        world.start(reminders, reconciliation, transactions, shifts, round, answering, connection)
        runBlocking {
            database.storySetting()
            server.shelf(SHARED_KIT, participants = 2)
            database.medKits().upsert(family.toMedKitStorageEntity())
            val packages = database.packageRepository()
            onTheShelf(opened, "Ибупрофен початый", "6")
            onTheShelf(his, "Ибупрофен мужа", "12")
            packages.add(pack(id = spare, name = "Ибупрофен про запас", quantity = tablets("20"), form = TABLET_FORM))
            // Лечение начато с новой пачки: остальные Нина подключает сама, и порядок пока не её.
            back = database.treatmentStarted(
                moscow(monday, 9, 0), "Спина", spare, monday,
                listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))
            )
            val plan = requireNotNull(database.courseRepository().findPlan(back))
            val saved = Scenarios(database, world.clock.now).sourceEditing.save(
                back, plan.revision,
                listOf(
                    SourceEditing.Source(spare, Doses(8)),
                    SourceEditing.Source(opened, Doses(6)),
                    SourceEditing.Source(his, Doses(6))
                )
            )
            assertTrue("завязка не записалась: $saved", saved is SourceEditing.Outcome.Saved)
            server.synchronization(database, world.clock).synchronize()
        }
        compose.setContent {
            MedAppTheme { MedAppShell(opening = world.opening.value, onOpened = { world.opening.value = null }) }
        }
    }

    @After
    fun tearDown() = world.end()

    /**
     * Нина ставит початую первой — пальцем, за карточку. Дальше её очередь трогает не она: муж
     * берёт две таблетки с общей полки, потом свою коробку с полки убирает. Снимок сервера
     * пересчитывает обеспечение, но очередь оставляет Нинину: иначе следующая доза пошла бы из
     * новой пачки, а початая долежала бы до конца срока и ушла в мусор — и Нина об этом не
     * узнала бы, потому что переставляла не она.
     */
    @Test
    fun theQueueSheSetWithHerFingerSurvivesWhatOthersDo() {
        sheDragsTheOpenedBoxToTheTop()
        herHusbandSpendsFromTheShelf()
        hisBoxLeavesTheShelf()
    }

    /** Источники лечения: початая перетаскивается наверх всей карточкой и записывается. */
    private fun sheDragsTheOpenedBoxToTheTop() {
        // Оболочка появляется не мгновенно: до первого чтения места ждут, и подвала ещё нет.
        compose.waitUntil(WAIT) { shown("План") }
        compose.onNodeWithText("План").performClick()
        compose.waitUntil(WAIT) { shown("Спина") }
        compose.onNodeWithText("Спина").performClick()
        compose.waitUntil(WAIT) { shown("Источники лечения") }
        compose.onNodeWithText("Источники лечения").performClick()
        compose.waitUntil(WAIT) { shown("Ибупрофен початый") }
        assertEquals(listOf(spare, opened, his), savedQueue())

        // Палец ложится на название — мимо значка ручки — и ведёт строку на место соседа.
        val step = rowTop("Ибупрофен початый") - rowTop("Ибупрофен про запас")
        compose.onNodeWithText("Ибупрофен початый").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Ибупрофен початый").performTouchInput { moveBy(Offset(0f, -step)) }
        compose.onNodeWithText("Ибупрофен початый").performTouchInput { up() }

        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(WAIT) { savedQueue() == listOf(opened, spare, his) }
    }

    /** Муж берёт две таблетки из початой: снимок уменьшает остаток, а очередь не трогает. */
    private fun herHusbandSpendsFromTheShelf() {
        world.moveTo(moscow(monday, 13, 0))
        server.drugs.getValue(opened).quantity = BigDecimal("4")
        server.drugs.getValue(opened).version++
        runBlocking { server.synchronization(database, world.clock).synchronize() }

        compose.waitUntil(WAIT) {
            runBlocking { database.packageRepository().find(opened) }?.quantity?.amount == BigDecimal("4")
        }
        assertEquals("снимок остатка очередь не переставляет", listOf(opened, spare, his), savedQueue())
    }

    /** Муж убирает свою коробку с полки: её нет в составе, а остальные стоят как стояли. */
    private fun hisBoxLeavesTheShelf() {
        world.moveTo(moscow(monday, 18, 0))
        server.drugs.remove(his)
        runBlocking { server.synchronization(database, world.clock).synchronize() }

        compose.waitUntil(WAIT) { savedQueue()?.contains(his) == false }
        assertEquals("ушедшая коробка не переставляет оставшиеся", listOf(opened, spare), savedQueue())
    }

    /** Коробка на общей полке: сервер о ней знает, значит снимок может её и тронуть, и унести. */
    private suspend fun onTheShelf(id: Uuid, name: String, quantity: String) {
        server.drug(id, SHARED_KIT, name = name, quantity = quantity)
        database.packageRepository().add(
            pack(id = id, name = name, medKit = family.ref, quantity = tablets(quantity), form = TABLET_FORM),
            PackageSyncState(id, ResourceVersion(1), ResourceVersion(1), syncedAt = moscow(monday, 9, 0))
        )
    }

    /** Очередь, записанная в план: из чего лечение возьмёт раньше. */
    private fun savedQueue(): List<Uuid>? =
        runBlocking { database.courseRepository().findPlan(back) }?.sources?.map { it.pkg.id }

    /** Где строка начинается: порядок на экране читается по положению, а не по месту в дереве. */
    private fun rowTop(name: String): Float =
        compose.onNodeWithText(name).fetchSemanticsNode().positionInRoot.y

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
