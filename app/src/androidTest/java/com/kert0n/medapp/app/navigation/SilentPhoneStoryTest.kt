package com.kert0n.medapp.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.TestPermissions
import com.kert0n.medapp.fixture.allowNotifications
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
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
 * **Истории молчавшего телефона** (PLAN U1 «история человека», `docs/истории.md`).
 *
 * Уведомления — самая непроверяемая руками часть приложения: между «приложение должно было сказать»
 * и «человек пришёл сам» проходят дни, а подгадать их живому человеку нечем. В проверке эти дни
 * назначаются строкой, поэтому историй здесь три, и каждая — свой человеческий поворот.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SilentPhoneStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    private val WAIT = 5_000L

    /** Сегодняшний день человека — от него отсчитываются «три дня назад» и «вчера». */
    private val today: LocalDate = LocalDate.now(MOSCOW)

    @Before
    fun setUp() {
        allowNotifications()
        hilt.inject()
        runBlocking {
            database.vocabulary().save(
                units = listOf(TABLETS).map { it.toStorageEntity() },
                forms = listOf(TABLET_FORM).map { it.toStorageEntity() }
            )
            database.medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
            database.packageRepository()
                .add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM))
        }
    }

    @After
    fun tearDown() {
        TestPermissions.reset()
    }

    /**
     * **Максим.** Врач выписал ему курс на три дня, и Максим завёл его в приложении в тот же вечер.
     * Когда система спросила разрешение на уведомления, он отмахнулся — торопился, а диалог был
     * очередным из тех, что он закрывает не читая. С этой минуты приложение онемело, но Максим об
     * этом не знал: он был уверен, что телефон напомнит, и все три дня ждал сигнала.
     *
     * Сигнала не было. На третий день он открыл приложение сам — посмотреть, не сбился ли счёт, —
     * и вот тут оно обязано сказать ему **две вещи сразу**: почему молчало (иначе Максим решит, что
     * лечение кончилось или сломалось) и что именно он пропустил (иначе ему нечего с этим делать).
     *
     * Самое плохое здесь — молчание. Приложение, которое просто показывает сегодняшний день, оставит
     * Максима в уверенности, что всё идёт по плану, а три пропущенные дозы так и останутся
     * неотвеченными: лечение посчитает их пропусками и растянется, а он не поймёт почему.
     *
     * Отвечает он прямо здесь же, не разыскивая вчерашние дни: страница дня листается только
     * вперёд, и без полки эти приёмы были бы недосягаемы вовсе.
     */
    @Test
    fun maximForgotToAllowNotificationsAndMeetsThreeSilentDays() {
        TestPermissions.notifications = false
        val courseId = treatmentStartedThreeDaysAgo()
        val overdue = overdueIntakes(courseId)
        assertTrue("завязка без пропусков: проверять нечего", overdue.size >= 2)
        promiseWeFailedToTell(overdue)
        start()

        // Первое, что он видит на дне, — почему телефон молчал.
        openTheDay()
        compose.onNodeWithText("Напоминания не приходят").assertIsDisplayed()

        // И что именно пропустил: обязательства стоят полкой, каждое со своим днём.
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("О чём не смогли напомнить").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Принял").onFirst().performClick()

        // Ответ записан по-настоящему: коробка похудела на дозу.
        compose.waitUntil(WAIT) { runBlocking { spent() == tablets("18") } }
    }

    /**
     * **Ольга.** Утро, она одевает ребёнка в садик, телефон в одной руке. Приложение показывает
     * приём, она тянется к «Принял» — и попадает в «Пропустил»: кнопки рядом, палец один, а
     * внимания на них нет. Таблетку она при этом выпила.
     *
     * Ошибка в приложении о лекарствах стоит дороже обычной: незамеченная, она превратится в
     * «пропуск» — лечение растянется на день, а вечером приложение попросит принять то, что уже
     * принято. Поэтому у ошибки должен быть **виден** и её след, и путь назад.
     *
     * След — слово «пропущен» в строке: без него Ольга не заметила бы, что нажала не туда. Путь
     * назад — кнопка «Принял», которая у пропущенного пункта остаётся: доза уехала вперёд, а не
     * пропала, и подтвердить её позже законно (PLAN D6). Отказ — решение человека, а не приговор
     * приложения, и переменить его он вправе.
     */
    @Test
    fun olgaPressedSkippedByMistakeAndFixesIt() {
        val courseId = treatmentStartedToday()
        start()
        openTheDay()

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Пропустил").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Пропустил").onFirst().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("пропущен").fetchSemanticsNodes().isNotEmpty() }

        // Ошибку видно, и рядом с ней — «Принял»: отказ не запирает пункт навсегда.
        compose.onAllNodesWithText("Принял").onFirst().performClick()
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("принят в", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            val taken = database.intakeRepository().ofCourse(courseId)
                .filterIsInstance<CourseIntake>()
                .count { it.status == IntakeStatus.TAKEN }
            assertEquals("исправленный приём не записан", 1, taken)
        }
    }

    /**
     * **Тимур.** Он уехал на три дня к родителям, телефон почти всё время лежал в сумке в режиме
     * полёта — в деревне связи нет, а батарею он берёг. Лекарства он с собой взял и пил их, как
     * велено, но отмечать было негде: приложение не открывалось.
     *
     * Вернувшись, он открывает его и видит три дня, о которых приложение не смогло сказать. Дальше
     * важны две вещи, и обе — про меру.
     *
     * Первая: на полке — **только наступившее**. Обязательства заводятся на каждый будущий приём,
     * и если показать их все, Тимур увидит там весь остаток лечения и не поймёт, что из этого
     * прошлое, а что — завтрашнее. О завтрашнем скажут завтра.
     *
     * Вторая: отвечает он **по одному**, и каждый ответ — настоящая запись со своим расходом. Кнопки
     * «отметить всё» здесь быть не должно: за каждой дозой стоит таблетка, которую он или выпил, или
     * нет, и врать об этом разом удобнее, чем по одной.
     */
    @Test
    fun timurReturnsFromATripToWhatHasPiledUp() {
        val courseId = treatmentStartedThreeDaysAgo()
        val overdue = overdueIntakes(courseId)
        promiseWeFailedToTell(overdue)
        start()
        openTheDay()

        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("О чём не смогли напомнить").fetchSemanticsNodes().isNotEmpty()
        }
        val piled = compose.onAllNodesWithText("Принял").fetchSemanticsNodes().size
        assertTrue("накопившегося не видно: $piled", piled >= 2)

        // Отвечает он по одному, и записывается ровно один приём: накопившееся не сваливается
        // в кучу «отметить всё» — за каждым стоит своя доза (PLAN D6).
        compose.onAllNodesWithText("Принял").onFirst().performClick()
        compose.waitUntil(WAIT) { runBlocking { takenCount(courseId) == 1 } }
        // Остальное ждёт на той же полке: разговор не окончен.
        compose.onNodeWithText("О чём не смогли напомнить").assertIsDisplayed()
    }

    /** Лечение, начатое три дня назад: пункты прошлых дней остались без ответа и стали пропусками. */
    private fun treatmentStartedThreeDaysAgo(): Uuid = runBlocking {
        val started = Instant.now().minus(3, ChronoUnit.DAYS)
        val past = Scenarios(database, started)
        val created = past.courseDrafting.create("Нурофен")
        val saved = past.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(
                    schedule(start = today.minusDays(3), times = listOf(LocalTime.of(9, 0)))
                ),
                CourseDrafting.Edit.SetTotalDoses(Doses(6)),
                CourseDrafting.Edit.Attach(PACK, Doses(6))
            )
        ) as CourseDrafting.Outcome.Saved
        past.courseActivation.activate(saved.draft.id, saved.draft.revision)
        // Сегодняшними часами прошлое приводится в порядок: неотвеченные дни стали пропусками.
        Scenarios(database, Instant.now()).courseUpkeep.keepUp()
        saved.draft.id
    }

    /** Лечение, начатое сегодня: у него один неотвеченный пункт — тот, о котором история. */
    private fun treatmentStartedToday(): Uuid = runBlocking {
        val scenarios = Scenarios(database, Instant.now())
        val created = scenarios.courseDrafting.create("Нурофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = today, times = listOf(LocalTime.of(9, 0)))),
                CourseDrafting.Edit.SetTotalDoses(Doses(4)),
                CourseDrafting.Edit.Attach(PACK, Doses(4))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        saved.draft.id
    }

    /** Пункты, чьё время уже прошло: о них и не смогли сказать. */
    private fun overdueIntakes(courseId: Uuid): List<CourseIntake> = runBlocking {
        database.intakeRepository().ofCourse(courseId)
            .filterIsInstance<CourseIntake>()
            .filter { it.slot.at.isBefore(Instant.now()) }
            .sortedBy { it.slot.at }
    }

    /** Обязательства «сказать не смогли» — такие же, какие заводит сверка (PLAN D8). */
    private fun promiseWeFailedToTell(intakes: List<CourseIntake>) = runBlocking {
        Scenarios(database, Instant.now()).reminderPromising.promise(
            intakes.map {
                Reminder(
                    key = NotificationKey.intake(it.id, NotificationKind.INTAKE_DUE),
                    target = NotificationTarget.Intake(it.id),
                    dueAt = it.slot.at
                )
            }
        )
    }

    private suspend fun spent() = database.packageRepository().find(PACK)?.quantity

    private suspend fun takenCount(courseId: Uuid) = database.intakeRepository().ofCourse(courseId)
        .filterIsInstance<CourseIntake>()
        .count { it.status == IntakeStatus.TAKEN }

    private fun start() {
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    /** Путь к странице дня: место «План» и его состояние «День». */
    private fun openTheDay() {
        compose.onNodeWithText("План").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("День").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("День").performClick()
    }
}
