package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.feature.connectivity.Connection
import com.kert0n.medapp.feature.notification.DailyRound
import com.kert0n.medapp.feature.notification.NotificationReconciliation
import com.kert0n.medapp.feature.notification.ReminderAnswering
import com.kert0n.medapp.fixture.FakeServer
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.StoryWorld
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.intakeOn
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
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Нины — разбирает аптечку в субботу вечером** (`docs/истории.md`, U5).
 *
 * Семь коробок истекают сегодня на двух полках, одна из них — источник её лечения, а на общей
 * дачной хозяйничает и муж. Время двигает рассказ ([StoryWorld]): полночь приходит, пока Нина на
 * карточке, а приложение живёт в памяти до понедельника.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ShelfClearingStoryTest {

    private val saturday: LocalDate = LocalDate.of(2027, 3, 20)

    private val world = StoryWorld.begin(moscow(saturday.minusDays(1), 20, 0), MOSCOW)

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
    private val dacha = medKit(id = SHARED_KIT, name = "Дача", publication = MedKit.Publication.PUBLISHED, participantCount = 2)

    private val boxes = linkedMapOf<String, Uuid>()
    private lateinit var course: Uuid

    @Before
    fun setUp() {
        hilt.inject()
        world.start(reminders, reconciliation, transactions, shifts, round, answering, connection)
        runBlocking {
            database.storySetting()
            server.shelf(SHARED_KIT)
            database.medKits().upsert(dacha.toMedKitStorageEntity())
            val packages = database.packageRepository()
            // Дома — четыре коробки со сроком сегодня и источник её лечения с тем же сроком.
            for (name in listOf("Нурофен", "Цетрин", "Но-шпа", "Аспирин", "Амоксиклав")) home(name, saturday)
            // Воскресенье и понедельник — новости следующих дней.
            home("Смекта", saturday.plusDays(1))
            home("Парацетамол", saturday.plusDays(1))
            home("Мирамистин", saturday.plusDays(2))
            // Кагоцел — источник следующего лечения, срок до пятницы: его «за три дня» — вторник.
            home("Кагоцел", saturday.plusDays(6))
            // На даче, общей с мужем, — ещё две со сроком сегодня.
            for (name in listOf("Уголь", "Лоратадин")) {
                val id = Uuid.random().also { boxes[name] = it }
                server.drug(id, SHARED_KIT, name = name, quantity = "10")
                packages.add(
                    pack(id = id, name = name, medKit = dacha.ref, quantity = tablets("10"), form = TABLET_FORM, expiresOn = ExpiryDate(saturday)),
                    PackageSyncState(id, ResourceVersion(1), ResourceVersion(1), syncedAt = moscow(saturday.minusDays(1), 20, 0))
                )
            }
            // Вчера вечером начала лечение и первый приём в 21:00 так и не отметила.
            course = database.treatmentStarted(
                moscow(saturday.minusDays(1), 20, 0), "Амоксиклав", boxes.getValue("Амоксиклав"),
                saturday.minusDays(1), listOf(LocalTime.of(21, 0))
            )
            // Следующее лечение начнётся после истории: пропусков и строк в попапах оно не даёт.
            database.treatmentStarted(
                moscow(saturday.minusDays(1), 20, 0), "Кагоцел", boxes.getValue("Кагоцел"),
                saturday.plusDays(7), listOf(LocalTime.of(9, 0))
            )
        }
        compose.setContent {
            MedAppTheme { MedAppShell(opening = world.opening.value, onOpened = { world.opening.value = null }) }
        }
    }

    @After
    fun tearDown() = world.end()

    /**
     * 23:40, суббота. Сначала попап пропущенного — вчерашний приём, — потом семь коробок в новости о
     * сроке. Нурофен выброшен, у Цетрина исправлен срок, Лоратадин выбросил муж на даче, полночь
     * застаёт Нину на карточке. Воскресенье — свои коробки, крестик; понедельник — новость
     * приходит, хоть попап и закрывали; во вторник Нина не входит, в среду вчерашней новости нет, а
     * источник следующего лечения, чей день «за три» пришёлся на пропущенный вторник, всё равно
     * предупреждён заранее.
     *
     * Стережёт: сначала пропуски, потом срок; попап не заслоняет карточку и ждёт возвращения;
     * содержимое живое — выброс, правка, чужой снимок; после полуночи попап не врёт про вчера;
     * закрытое помнится ключами, а не флагом; предупреждение заранее не пропадает вместе с
     * пропущенным днём.
     */
    @Test
    fun ninaClearsTheShelvesOnSaturdayNightAndTheNewsStaysTrue() {
        firstTheMissedIntakeThenTheExpiryNews()
        sheThrowsOneAwayAndCorrectsAnother()
        herHusbandClearsTheDachaMeanwhile()
        midnightFindsHerOnACard()
        mondaysNewsComesThoughSheClosedTheSundayOne()
        onWednesdayYesterdaysNewsIsGone()
        tuesdayWasMissedAndTheSourceIsStillWarnedAhead()
    }

    private fun firstTheMissedIntakeThenTheExpiryNews() {
        world.moveTo(moscow(saturday, 23, 40))
        runBlocking { world.enter() }
        compose.waitUntil(WAIT) { shown(MISSED) }
        compose.onNodeWithText(EXPIRY).assertDoesNotExist()
        compose.onNodeWithText("Принял").performClick()
        compose.waitUntil(WAIT) { shown(EXPIRY) }
        assertEquals(IntakeStatus.TAKEN, runBlocking { database.intakeOn(course, saturday.minusDays(1), 21).status })
        // Семь коробок — список листается до последней.
        for (name in listOf("Нурофен", "Цетрин", "Но-шпа", "Аспирин", "Амоксиклав", "Уголь", "Лоратадин")) {
            popupList()
                .performScrollToNode(hasClickAction() and hasText(name))
        }
    }

    /** Нурофен — выбросить; у Цетрина спутан год — исправить срок. Попапа над карточкой нет. */
    private fun sheThrowsOneAwayAndCorrectsAnother() {
        openFromPopup("Нурофен")
        compose.onNodeWithContentDescription("Выбросить").performClick()
        compose.waitUntil(WAIT) { shown("Выбросить лекарство?") }
        compose.onAllNodesWithText("Выбросить").onLast().performClick()
        compose.waitUntil(WAIT) { shown(EXPIRY) && !shown("Нурофен") }

        openFromPopup("Цетрин")
        runBlocking {
            val scenarios = Scenarios(database, world.clock.now)
            val box = requireNotNull(database.packageRepository().find(boxes.getValue("Цетрин")))
            scenarios.packageDescribing.describe(box.id, box.facts.copy(expiresOn = ExpiryDate(saturday.plusYears(1))))
        }
        back()
        compose.waitUntil(WAIT) { shown(EXPIRY) && !shown("Цетрин") }
    }

    /** Пока Нина на Но-шпе, муж выбросил Лоратадин на даче — снимок убирает его из новости. */
    private fun herHusbandClearsTheDachaMeanwhile() {
        openFromPopup("Но-шпа")
        server.drugs.remove(boxes.getValue("Лоратадин"))
        runBlocking { server.synchronization(database, world.clock).synchronize() }
        back()
        compose.waitUntil(WAIT) { shown(EXPIRY) && !shown("Лоратадин") }
        // Уголь остался: на крупном шрифте он ниже видимой части списка — к нему листают.
        popupList().performScrollToNode(hasClickAction() and hasText("Уголь"))
    }

    /**
     * 23:59 на карточке; полночь. Субботний вечерний антибиотик она выпила в девять и не отметила —
     * в полночь он стал пропуском, и **первым** вернувшуюся встречает попап пропущенного, а уже за
     * ним — воскресные коробки, а не вчерашние.
     */
    private fun midnightFindsHerOnACard() {
        openFromPopup("Аспирин")
        world.moveTo(moscow(saturday, 23, 59))
        world.moveTo(moscow(saturday.plusDays(1), 0, 1))
        runBlocking { world.enter() }
        back()
        compose.waitUntil(WAIT) { shown(MISSED) }
        compose.onNodeWithText(EXPIRY).assertDoesNotExist()
        compose.onNodeWithText("Принял").performClick()
        compose.waitUntil(WAIT) { shown(EXPIRY) && shown("Смекта") }
        assertEquals(IntakeStatus.TAKEN, runBlocking { database.intakeOn(course, saturday, 21).status })
        compose.onNodeWithText("Парацетамол").assertExists()
        compose.onNodeWithText("Аспирин").assertDoesNotExist()
        compose.onNodeWithText("Уголь").assertDoesNotExist()
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.waitUntil(WAIT) { !shown(EXPIRY) }
    }

    /** Окно живо до понедельника; закрытый в воскресенье попап приносит понедельничную новость. */
    private fun mondaysNewsComesThoughSheClosedTheSundayOne() {
        world.moveTo(moscow(saturday.plusDays(2), 8, 0))
        runBlocking { world.enter() }
        // Воскресный вечерний забыла — и честно закрывает: пропуск.
        compose.waitUntil(WAIT) { shown(MISSED) }
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.waitUntil(WAIT) { shown(EXPIRY) && shown("Мирамистин") }
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.waitUntil(WAIT) { !shown(EXPIRY) }
    }

    /** Во вторник не вошла; в среду новости «сегодня истекает» про вторник нет — это была бы ложь. */
    private fun onWednesdayYesterdaysNewsIsGone() {
        world.moveTo(moscow(saturday.plusDays(4), 9, 0))
        runBlocking { world.enter() }
        // Понедельник и вторник без ответа — попап пропущенного; закрыт — новости о сроке нет.
        compose.waitUntil(WAIT) { shown(MISSED) }
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.waitUntil(WAIT) { !shown(MISSED) }
        runBlocking { world.settle() }
        compose.waitForIdle()
        compose.onNodeWithText(EXPIRY).assertDoesNotExist()
    }

    /**
     * Вторник был пропущен — а он и был днём «за три» у Кагоцела. В среду до конца срока два дня, и
     * шторка предупреждает заранее: купить ещё успеют. Прежде этап считался только в точный день, и
     * пропущенный вторник съедал предупреждение до последнего дня.
     */
    private fun tuesdayWasMissedAndTheSourceIsStillWarnedAhead() {
        val kagocel = boxes.getValue("Кагоцел")
        val warned = world.shade.shown.filter { (it.target as? NotificationTarget.PackageCard)?.packageId == kagocel }
        assertEquals(listOf(NotificationKind.EXPIRY_SOURCE_3D), warned.map { it.kind })
    }

    private fun home(name: String, expiresOn: LocalDate) {
        val id = Uuid.random().also { boxes[name] = it }
        runBlocking {
            database.packageRepository().add(pack(id = id, name = name, quantity = tablets("10"), form = TABLET_FORM, expiresOn = ExpiryDate(expiresOn)))
        }
    }

    private fun openFromPopup(name: String) {
        popupList()
            .performScrollToNode(hasClickAction() and hasText(name))
        compose.onNode(hasClickAction() and hasText(name) and hasAnyAncestor(hasScrollAction())).performClick()
        compose.waitUntil(WAIT) { shown("Сколько есть") }
        compose.onNodeWithText(EXPIRY).assertDoesNotExist()
    }

    /** Список попапа — последний прокручиваемый: под окном лежит ещё список аптечек. */
    private fun popupList() = compose.onAllNodes(hasScrollAction()).onLast()

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val MISSED = "Без ответа за прошлые дни"
        const val EXPIRY = "Сегодня истекает срок годности"
    }
}
