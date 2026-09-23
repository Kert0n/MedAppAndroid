package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.StoryWorld
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.moscow
import com.kert0n.medapp.feature.notification.DailyRound
import com.kert0n.medapp.feature.notification.NotificationReconciliation
import com.kert0n.medapp.feature.notification.ReminderAnswering
import com.kert0n.medapp.platform.time.TimeShifts
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.feature.connectivity.Connection
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.value.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Ирины** (PLAN U1 «история человека»). Врач называет лекарство и расписание, а записать
 * всё сразу негде: в коридоре Ирина заводит лечение одним названием, вечером покупает коробку и
 * только потом дописывает назначение и подключает источник.
 *
 * Проверка идёт её путём целиком — от пустого приложения до карточки идущего лечения, — потому что
 * дефекты этого набора жили **между** экранами: каждая проверка стояла на своём и кончалась там,
 * где беда начиналась. Так и нашлись руками: заполненный черновик получал «сначала укажите дозу и
 * форму», а до поля даты нельзя было дотянуться пальцем (находки владельца 2026-09-16).
 *
 * Начинает она в среду, а дату «сегодня» ставила в понедельник. Лечение с прошедшего дня не
 * начинают: иначе два дня стали бы пропусками, которых не было (решение владельца 2026-09-23).
 * Время двигает рассказ ([StoryWorld]).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PrescriptionStoryTest {

    private val monday: LocalDate = LocalDate.of(2027, 3, 8)

    private val wednesday: LocalDate = monday.plusDays(2)

    private val world = StoryWorld.begin(moscow(monday, 12, 0), MOSCOW)

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    @Inject lateinit var connection: Connection
    @Inject lateinit var reminders: ReminderStorageRepository
    @Inject lateinit var reconciliation: NotificationReconciliation
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var shifts: TimeShifts
    @Inject lateinit var round: DailyRound
    @Inject lateinit var answering: ReminderAnswering

    /**
     * Словарь — как на боевом сервере: таблетки считают **штуками**. Фикстурная единица зовётся
     * «таблетка» и с формой «таблетки» по имени не сходится, а Ирине важно, что форма приводит
     * единицу сама, — поэтому здесь стоит та пара, которую человек видит в жизни.
     */
    private val pieces = QuantityUnit(Uuid.parse("00000000-0000-4000-8000-0000000000a1"), "шт")

    /**
     * Ожидание с запасом: история ждёт состояния, пришедшего из базы, и секунды по умолчанию на
     * это не хватает — краснело бы не о коде, а о занятости машины.
     */
    private val WAIT = 5_000L

    @Before
    fun setUp() {
        hilt.inject()
        world.start(reminders, reconciliation, transactions, shifts, round, answering, connection)
        runBlocking {
            database.vocabulary().save(
                units = listOf(pieces, TABLETS, MILLILITRES).map { it.toStorageEntity() },
                forms = listOf(TABLET_FORM, CAPSULE_FORM).map { it.toStorageEntity() }
            )
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    @After
    fun tearDown() = world.end()

    @Test
    fun irinaWritesAPrescriptionInTheCorridorAndSuppliesItAtHome() {
        inTheCorridorSheWritesDownTheName()
        atHomeSheAddsTheBoxSheBought()
        thenSheFillsInThePrescription()
        sheAttachesTheBoxAndAllocatesDoses()
        onWednesdayTheMondayStartHasPassed()
    }

    /** У врача времени нет: одно название и заметка. Черновик виден в списке и говорит, что записано. */
    private fun inTheCorridorSheWritesDownTheName() {
        compose.onNodeWithText("План").performClick()
        // До первого чтения место «План» ждёт, а не показывает пустоту (U1): дожидаемся.
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Записать лечение").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Записать лечение").performClick()
        compose.onNodeWithText("Название").performTextInput("Нурофен")
        compose.onNodeWithText("Заметка (необязательно)").performTextInput("по 2 после еды, купить")
        // Подвал уходит на время ввода: человек убирает клавиатуру, чтобы нажать.
        closeSoftKeyboard()
        compose.onNodeWithText("Сохранить").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Черновики").fetchSemanticsNodes().isNotEmpty() }
        // Строка черновика говорит тем, что записано: заметкой, раз назначения ещё нет.
        compose.onNodeWithText("по 2 после еды, купить").assertIsDisplayed()
    }

    /** Вечером она покупает коробку и кладёт её на полку. */
    private fun atHomeSheAddsTheBoxSheBought() {
        world.moveTo(moscow(monday, 19, 0))
        compose.onNodeWithText("Аптечки").performClick()
        compose.onNodeWithText("Завести аптечку").performClick()
        compose.onNodeWithText("Название").performTextInput("Домашняя")
        // Подвал уходит на время ввода: человек убирает клавиатуру, чтобы нажать.
        closeSoftKeyboard()
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Пока пусто").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Домашняя").performClick()
        compose.onNodeWithText("Завести лекарство").performClick()
        compose.onNodeWithText("Название").performTextInput("Нурофен")
        compose.onNodeWithText("Форма выпуска").performScrollTo().performClick()
        compose.onNodeWithText("таблетки").performClick()
        compose.onNodeWithText("Количество").performScrollTo().performTextInput("20")
        // Подвал уходит на время ввода: человек убирает клавиатуру, чтобы нажать.
        closeSoftKeyboard()
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Сколько есть").fetchSemanticsNodes().isNotEmpty() }
        // Заведённая коробка открылась карточкой; в места приложения человек возвращается назад.
        back()
        back()
    }

    /**
     * Черновик открывается **из списка** и дописывается: форма выпуска приводит с собой единицу.
     * «Сохранить» Ирина не жмёт — ей нужны источники, и набранное записывает переход к ним (H3 §15).
     */
    private fun thenSheFillsInThePrescription() {
        compose.onNodeWithText("План").performClick()
        compose.onNodeWithText("Нурофен").performClick()

        compose.onNodeWithText("Форма выпуска").performScrollTo().performClick()
        compose.onNodeWithText("таблетки").performClick()
        // Единица встала сама: мерить дозу человеку уже есть чем.
        compose.onNodeWithText("шт").assertIsDisplayed()
        compose.onNodeWithText("Доза").performScrollTo().performTextInput("2")

        // Дозу Ирина набрала — клавиатура ей больше не нужна, и она её убирает.
        closeSoftKeyboard()
        sheSetsTheStartByTappingTheFieldItself(monday)

        // Каждый день в девять утра — так сказал врач.
        for (day in DayOfWeek.entries) {
            compose.onNodeWithText(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())).performScrollTo().performClick()
        }
        compose.onNodeWithText("Добавить время").performScrollTo().performClick()
        compose.onNode(hasText("Выбрать") and hasAnyAncestor(isDialog())).performClick()

        // Сколько всего приёмов, врач сказал: без этого числа делить коробку не на что (H3 §16).
        compose.onNodeWithText("Всего приёмов").performScrollTo().performTextInput("14")
    }

    /**
     * Дату Ирина назначает пальцем — нажатием **по полю**, а не по значку: до значка в 24 dp она
     * не всегда дотягивается (находка владельца 2026-09-16). День она набирает в календаре
     * цифрами: ячейки сетки Material проверке не адресуются, а ввод текстом — тот же календарь.
     */
    private fun sheSetsTheStartByTappingTheFieldItself(day: LocalDate) {
        compose.onNodeWithText("С какого дня").performScrollTo().performClick()

        // Календарь приезжает с показом: на плотном экране он успевает не сразу.
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithContentDescription("Перейти в режим ввода текста").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Перейти в режим ввода текста").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
            .performTextReplacement("%02d%02d%04d".format(day.dayOfMonth, day.monthValue, day.year))
        compose.onNode(hasText("Выбрать") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("%02d.%02d.%04d".format(day.dayOfMonth, day.monthValue, day.year), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Коробка подключается — отказа «сначала укажите дозу и форму» нет: набранное записалось при
     * переходе. Ползунком Ирина отдаёт лечению семь приёмов и записывает состав.
     */
    private fun sheAttachesTheBoxAndAllocatesDoses() {
        compose.onNodeWithText("Источники лечения").performScrollTo().performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Подключить ещё препарат").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Подключить ещё препарат").performClick()

        compose.onNodeWithText("Сначала укажите дозу и форму лечения.").assertDoesNotExist()
        compose.onNodeWithText("Нурофен").performClick()

        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("выделено 0 приёмов", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Предел строки назван у поля — и чтецу целиком, — а набирают в само поле: на экране оно одно.
        compose.onNodeWithContentDescription("Приёмов из 10").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("7")
        // Подвал уходит на время ввода: человек убирает клавиатуру, чтобы нажать.
        closeSoftKeyboard()
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("выделено 7 приёмов", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Дела отложили начало до среды. Дата «сегодня», поставленная в понедельник, прошла, пока
     * черновик лежал, и «Начать лечение» не начинает с понедельника: поле начала просит выбрать
     * день заново. Ирина ставит среду — и лечение идёт без единого пропуска.
     */
    private fun onWednesdayTheMondayStartHasPassed() {
        // От источников назад к форме и к «Плану», где черновик лежит в списке.
        back()
        back()
        world.moveTo(moscow(wednesday, 8, 0))
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Нурофен").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Нурофен").performClick()

        compose.onNodeWithText("Начать лечение").performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Лечение не начинается раньше сегодняшнего дня", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val draft = runBlocking { database.courseRepository().observeDrafts().first().single() }

        sheSetsTheStartByTappingTheFieldItself(wednesday)
        compose.onNodeWithText("Начать лечение").performClick()

        compose.waitUntil(WAIT) { runBlocking { database.courseRepository().findPlan(draft.id) } != null }
        val plan = runBlocking { requireNotNull(database.courseRepository().findPlan(draft.id)) }
        assertEquals(wednesday, plan.schedule.start)
        // Понедельник и вторник не стали пропусками: спрашивать о прошлых днях не о чем.
        compose.onNodeWithText("Без ответа за прошлые дни").assertDoesNotExist()
    }

    /** Возврат кнопкой: у каждого экрана набора она в панели. */
    private fun back() = compose.onNodeWithContentDescription("Назад").performClick()
}
