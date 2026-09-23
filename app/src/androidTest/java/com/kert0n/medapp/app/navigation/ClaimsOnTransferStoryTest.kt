package com.kert0n.medapp.app.navigation

import android.content.ClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.course.CourseSchedule
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.ProbeAccounts
import com.kert0n.medapp.fixture.removeFromProd
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.storySetting
import com.kert0n.medapp.network.pack.ClaimPostNetworkDTO
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.value.toDosageForm
import com.kert0n.medapp.network.value.toQuantityUnit
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Галины, Егора и Тимура — коробка ушла на полку, которую видят не все**
 * (`docs/истории.md`, U6).
 *
 * Людей трое и полок две: дачную видят все, городскую — только Галина и Егор. Идёт **живьём**:
 * приложение живёт учёткой A и ведёт Галину по экранам, Егор и Тимур — два других клиента против
 * того же боевого сервера (AGENTS «Связь с сервером»). Их брони настоящие, и снимает их сервер
 * сам — по своему правилу «бронь переживает переезд, только если её хозяин видит цель», а не по
 * заказу проверки.
 *
 * Стережёт: предупреждение о чужих бронях стоит **до** выбора полки; перенос одной коробки
 * оставляет брони тех, кто цель видит, и снимает бронь того, кто не видит; лечение того, кто цель
 * видит, переезд не трогает — ни дозой, ни расписанием, ни источником; уборка полки целиком с
 * переносом ведёт себя так же, как перенос одной коробки.
 *
 * Включается только `-Pprobe` и только при третьей учётке. Синтетические полки история удаляет за
 * собой всеми тремя участниками.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ClaimsOnTransferStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    private val WAIT = 30_000L
    private val summer = medKit(id = Uuid.random(), name = "Дачная Галины")
    private val city = medKit(id = Uuid.random(), name = "Городская Галины")
    private val nurofen = Uuid.random()
    private val hexoral = Uuid.random()
    private lateinit var back: Uuid
    private lateinit var throat: Uuid
    private lateinit var unit: QuantityUnit
    private lateinit var form: DosageForm

    private fun <T> success(result: ApiResult<T>): T = when (result) {
        is ApiResult.Success -> result.value
        is ApiResult.Failure -> error("боевой сервер отказал: $result")
    }

    private val egor: MedAppApi get() = requireNotNull(ProbeAccounts.boris)
    private val timur: MedAppApi get() = requireNotNull(ProbeAccounts.viktor)

    @Before
    fun setUp() {
        val why = ProbeAccounts.thirdSkipReason
        assumeTrue(why.orEmpty(), why == null)
        hilt.inject()
        runBlocking {
            database.storySetting()
            // Словарь — у самого сервера: выдуманных единиц он не знает и коробку с ними не примет.
            val galina = requireNotNull(ProbeAccounts.anna)
            val units = success(galina.quantityUnits()).map { it.toQuantityUnit() }
            val forms = success(galina.formTypes()).map { it.toDosageForm() }
            database.vocabulary().save(
                units = units.map { it.toStorageEntity() },
                forms = forms.map { it.toStorageEntity() }
            )
            unit = units.first()
            form = forms.first()
            database.medKits().upsert(summer.toStorageEntity())
            database.medKits().upsert(city.toStorageEntity())
            val packages = database.packageRepository()
            packages.add(
                pack(
                    id = nurofen, name = "Нурофен дачный", medKit = summer.ref,
                    quantity = Quantity(BigDecimal("30"), unit), form = form
                )
            )
            packages.add(
                pack(
                    id = hexoral, name = "Гексорал дачный", medKit = summer.ref,
                    quantity = Quantity(BigDecimal("30"), unit), form = form
                )
            )
            // Лечения Галины держат обе коробки: бронь у сервера появляется именно из выделения
            // курса — публикация полки везёт её следом за созданием коробки (PLAN E6).
            back = treatment("Спина", nurofen)
            throat = treatment("Горло", hexoral)
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    /** Лечение на десять дней из одной коробки — дозой и формой боевого словаря, а не фикстуры. */
    private suspend fun treatment(title: String, box: Uuid): Uuid {
        val scenarios = Scenarios(database, Instant.now())
        val created = scenarios.courseDrafting.create(title)
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose(Quantity(BigDecimal.ONE, unit))),
                CourseDrafting.Edit.SetForm(form),
                CourseDrafting.Edit.SetSchedule(
                    CourseSchedule(
                        start = LocalDate.now(),
                        daysOfWeek = java.time.DayOfWeek.entries.toSet(),
                        times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)),
                        zone = java.time.ZoneId.systemDefault()
                    )
                ),
                CourseDrafting.Edit.SetTotalDoses(Doses(20)),
                CourseDrafting.Edit.Attach(box, Doses(20))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        return saved.draft.id
    }

    @After
    fun tearDown() = runBlocking {
        if (ProbeAccounts.thirdSkipReason != null) return@runBlocking
        val everyone = listOfNotNull(ProbeAccounts.anna, ProbeAccounts.boris, ProbeAccounts.viktor)
        removeFromProd(listOf(summer.id, city.id), everyone)
    }

    /**
     * Обе полки становятся общими, соседи входят и заявляют своё, Галина увозит коробку в город, а
     * через неделю увозит и всю полку.
     */
    @Test
    fun aTransferKeepsTheClaimsOfThoseWhoSeeTheTarget() {
        galinaSharesBothShelves()
        egorAndTimurJoinTheSummerShelf()
        egorJoinsTheCityShelf()
        everyoneClaimsBothBoxes()
        galinaMovesTheNurofenToTheCity()
        theClaimsOfThoseWhoSeeTheCityStayed(nurofen)
        galinaClearsTheSummerShelfIntoTheCity()
        theClaimsOfThoseWhoSeeTheCityStayed(hexoral)
        herTreatmentsAreUntouched()
    }

    private fun galinaSharesBothShelves() {
        publish(summer.name)
        publish(city.name)
    }

    /** Полка уезжает, и пока решение в пути, звать в неё некуда — это сказано словами. */
    private fun publish(shelf: String) {
        openShelf(shelf)
        compose.onNodeWithContentDescription("Что сделать с аптечкой").performClick()
        compose.onNodeWithText("Поделиться аптечкой").performClick()
        compose.waitUntil(WAIT) { shown("Станет общим") }
        compose.onNodeWithText("Сделать общей").performClick()
        compose.onAllNodesWithText("Сделать общей").onLast().performClick()
        compose.waitUntil(WAIT) {
            shown("Решение об аптечке уже едет серверу. Пригласить можно будет, когда оно доедет.")
        }
        refreshFromOptions()
    }

    private fun egorAndTimurJoinTheSummerShelf() {
        val code = invitationTo(summer.name)
        join(egor, code, "Егор")
        join(timur, invitationTo(summer.name), "Тимур")
    }

    private fun egorJoinsTheCityShelf() = join(egor, invitationTo(city.name), "Егор")

    private fun join(api: MedAppApi, code: String, who: String) = runBlocking {
        val joined = api.joinMedKit(MembershipPostNetworkDTO(code))
        check(joined is ApiResult.Success) { "$who не вошёл по коду: $joined" }
    }

    /** Код берётся из буфера — оттуда же, откуда его возьмёт человек, чтобы переслать. */
    private fun invitationTo(shelf: String): String {
        openShelf(shelf)
        compose.onNodeWithContentDescription("Что сделать с аптечкой").performClick()
        compose.onNodeWithText("Пригласить").performClick()
        compose.waitUntil(WAIT) { shown("Пригласить") }
        compose.onNodeWithText("Пригласить").performClick()
        compose.onAllNodesWithText("Пригласить").onLast().performClick()
        compose.waitUntil(WAIT) { shown("Код скопирован — его можно переслать") }
        var code: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            code = compose.activity.getSystemService(ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.text?.toString()
        }
        return requireNotNull(code?.takeIf { it.isNotBlank() }) { "код приглашения не лёг в буфер" }
    }

    /** Соседи заявляют по три единицы на каждую коробку: планы у всех троих, а коробка одна. */
    private fun everyoneClaimsBothBoxes() = runBlocking {
        for (box in listOf(nurofen, hexoral)) {
            for ((who, api) in listOf("Егор" to egor, "Тимур" to timur)) {
                // Бронь ставится по прочитанной картине броней: сервер требует её версию, и
                // второй заявитель обязан увидеть первого (PLAN E1).
                val seen = success(api.packageSnapshot(box)).claims.version
                val claimed = api.createClaim(ClaimPostNetworkDTO(box, "3", seen))
                check(claimed is ApiResult.Success) { "$who не смог заявить на коробку: $claimed" }
            }
        }
    }

    /**
     * Предупреждение стоит **до** выбора полки: назвать пострадавшего приложение не может — кто
     * состоит в городской, знает сервер, а не телефон (PLAN E6).
     */
    private fun galinaMovesTheNurofenToTheCity() {
        refreshFromOptions()
        openShelf(summer.name)
        compose.waitUntil(WAIT) { shown("Нурофен дачный") }
        compose.onNodeWithText("Нурофен дачный").performClick()
        compose.waitUntil(WAIT) { shown("Сколько есть") }
        compose.onNodeWithText("Перенести").performClick()

        compose.waitUntil(WAIT) { shown("Куда перенести") }
        compose.onNodeWithText(
            "На это лекарство заявили и другие. Кто не видит выбранную аптечку — потеряет свою бронь."
        ).assertIsDisplayed()
        compose.onNodeWithText(city.name).performClick()
        compose.onAllNodesWithText("Перенести").onLast().performClick()

        refreshFromOptions()
        openShelf(city.name)
        compose.waitUntil(WAIT) { shown("Нурофен дачный") }
    }

    /** Полку убирают целиком, содержимое — в городскую: то же правило, но на всей полке сразу. */
    private fun galinaClearsTheSummerShelfIntoTheCity() {
        openShelf(summer.name)
        compose.waitUntil(WAIT) { shown("Гексорал дачный") }
        compose.onNodeWithContentDescription("Что сделать с аптечкой").performClick()
        compose.onNodeWithText("Убрать").performClick()
        compose.waitUntil(WAIT) { shown("Перенести и убрать") }
        compose.onNodeWithText("Перенести и убрать").performClick()
        compose.waitUntil(WAIT) { shown("Куда перенести лекарства?") }
        compose.onNodeWithText(city.name).performClick()
        compose.onAllNodesWithText("Перенести и убрать").onLast().performClick()

        refreshFromOptions()
        openShelf(city.name)
        compose.waitUntil(WAIT) { shown("Гексорал дачный") }
    }

    /**
     * Две брони из трёх: Галина и Егор городскую видят, Тимур — нет. Спрашивается это у сервера
     * каждым из троих своими глазами: снимок Галины о чужих бронях знает только сумму.
     */
    private fun theClaimsOfThoseWhoSeeTheCityStayed(box: Uuid) = runBlocking {
        val galina = requireNotNull(ProbeAccounts.anna)
        assertClaim("бронь Галины держится за её же лечение", "20", claimAmount(galina, box))
        assertClaim("Егор видит городскую — его бронь переехала с коробкой", "3", claimAmount(egor, box))

        val timursClaim = timur.claim(box)
        assertTrue(
            "бронь Тимура на невидимую ему коробку: $timursClaim",
            timursClaim is ApiResult.Failure && timursClaim.failure == ApiFailure.NotFound
        )
        val timursBox = timur.packageSnapshot(box)
        assertTrue(
            "коробка осталась видна Тимуру после переезда: $timursBox",
            timursBox is ApiResult.Failure && timursBox.failure == ApiFailure.NotFound
        )
    }

    private suspend fun claimAmount(api: MedAppApi, box: Uuid): BigDecimal = BigDecimal(success(api.claim(box)).amount)

    /** Сверяется число, а не запись: сколько знаков после запятой напишет сервер — его дело. */
    private fun assertClaim(what: String, expected: String, amount: BigDecimal) =
        assertEquals("$what: $amount", 0, BigDecimal(expected).compareTo(amount))

    /**
     * Переехало **место**, а не назначение: у обоих лечений Галины прежние дозы, дни и источники.
     * Это и есть «планы не сломались» с её стороны; со стороны Егора то же самое говорит его
     * уцелевшая бронь — приложения у него в этой истории нет.
     */
    private fun herTreatmentsAreUntouched() = runBlocking {
        val courses = database.courseRepository()
        for ((course, box) in listOf(back to nurofen, throat to hexoral)) {
            val plan = requireNotNull(courses.findPlan(course)) { "лечение пропало вместе с полкой" }
            assertEquals("источник лечения", listOf(box), plan.sources.map { it.pkg.id })
            assertEquals("выделение лечения", Doses(20), plan.sources.single().allocatedDoses)
            assertEquals("доза лечения", dose(Quantity(BigDecimal.ONE, unit)), plan.dose)
            assertEquals("часы лечения", listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)), plan.schedule.times)
        }
        Unit
    }

    /** К местам — возвратами: панели мест в глубине нет, она у мест (PLAN H3 «Оболочка»). */
    private fun outToPlaces() {
        repeat(5) {
            if (shown("Опции")) return
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        }
        compose.waitUntil(WAIT) { shown("Опции") }
    }

    /** Полка открывается из места «Аптечки»: заголовок места и его подпись — два разных узла. */
    private fun openShelf(name: String) {
        outToPlaces()
        compose.onAllNodesWithText("Аптечки").onLast().performClick()
        compose.waitUntil(WAIT) { shown(name) }
        compose.onAllNodesWithText(name).onFirst().performClick()
        compose.waitUntil(WAIT) { described("Что сделать с аптечкой") }
    }

    /** Заход целиком — очередь и снимок: там, где об обмене и говорят (PLAN H3 №28). */
    private fun refreshFromOptions() {
        outToPlaces()
        compose.onAllNodesWithText("Опции").onLast().performClick()
        compose.waitUntil(WAIT) { shown("Синхронизация") }
        compose.onAllNodesWithText("Синхронизация").onFirst().performClick()
        compose.waitUntil(WAIT) { described("Обновить") }
        compose.onNodeWithContentDescription("Обновить").performClick()
        try {
            compose.waitUntil(WAIT) { shownPart("Последний обмен в") }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("заход не кончился; на экране: ${screenTexts()}", timeout)
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun shownPart(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun described(text: String) =
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    /** Что человек сейчас видит — словами: без этого отказ сервера читается только в журнале. */
    private fun screenTexts(): String {
        val nodes = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
        val words = mutableListOf<String>()
        for (node in nodes) {
            val texts: List<AnnotatedString>? = node.config.getOrNull(SemanticsProperties.Text)
            texts?.forEach { words += it.text }
        }
        return words.distinct().joinToString(" | ")
    }
}
