package com.kert0n.medapp.app.navigation

import android.content.ClipboardManager
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.course.CourseSchedule
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.ProbeAccounts
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.allowNotifications
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.pressAfterTyping
import com.kert0n.medapp.fixture.storySetting
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.pack.ClaimPostNetworkDTO
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
import kotlinx.coroutines.flow.first
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
 * **История Марины и Сергея «Одна коробка на два лечения»** (`docs/истории.md`). Коробка на сто
 * таблеток общая, и половину её Сергей заявил своим лечением — живьём, своим клиентом на боевом
 * сервере. Марина видит, что запланировать из коробки может только свободное, и что таблетка сверх
 * плана из занятого спрашивает, прежде чем записаться.
 *
 * Включается только `-Pprobe`. Синтетическую полку история удаляет за собой обоими участниками.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NeighboursClaimsStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    private val WAIT = 30_000L
    private val shelf = medKit(id = Uuid.random(), name = "Общая Марины")
    private val box = Uuid.random()
    private lateinit var unit: QuantityUnit
    private lateinit var form: DosageForm

    private fun <T> success(result: ApiResult<T>): T = when (result) {
        is ApiResult.Success -> result.value
        is ApiResult.Failure -> error("боевой сервер отказал: $result")
    }

    private val sergey: MedAppApi get() = requireNotNull(ProbeAccounts.boris)

    @Before
    fun setUp() {
        val why = ProbeAccounts.skipReason
        assumeTrue(why.orEmpty(), why == null)
        // Начало лечения спрашивает разрешение на уведомления, и системный диалог закрыл бы окно.
        allowNotifications()
        hilt.inject()
        runBlocking {
            database.storySetting()
            // Словарь — у самого сервера: выдуманных единиц он не знает и коробку с ними не примет.
            val marina = requireNotNull(ProbeAccounts.anna)
            val units = success(marina.quantityUnits()).map { it.toQuantityUnit() }
            val forms = success(marina.formTypes()).map { it.toDosageForm() }
            database.vocabulary().save(
                units = units.map { it.toStorageEntity() },
                forms = forms.map { it.toStorageEntity() }
            )
            unit = units.first()
            form = forms.first()
            database.medKits().upsert(shelf.toStorageEntity())
            database.packageRepository().add(
                pack(id = box, name = "Ибупрофен на сотню", medKit = shelf.ref, quantity = Quantity(BigDecimal("100"), unit), form = form)
            )
            herDraftFromTheDoctor()
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    /**
     * Черновик записан у врача: по таблетке на шестьдесят дней, коробка подключена на все шестьдесят.
     * Черновик коробок не читает и никого не занимает — путь к нему проходит история Ирины.
     */
    private suspend fun herDraftFromTheDoctor() {
        val scenarios = Scenarios(database, Instant.now())
        val created = scenarios.courseDrafting.create("Голова Марины")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose(Quantity(BigDecimal.ONE, unit))),
                CourseDrafting.Edit.SetForm(form),
                CourseDrafting.Edit.SetSchedule(
                    CourseSchedule(
                        start = LocalDate.now(),
                        daysOfWeek = java.time.DayOfWeek.entries.toSet(),
                        times = listOf(LocalTime.of(21, 0)),
                        zone = java.time.ZoneId.systemDefault()
                    )
                ),
                CourseDrafting.Edit.SetTotalDoses(Doses(60)),
                CourseDrafting.Edit.Attach(box, Doses(60))
            )
        )
        assertTrue("черновик не записался: $saved", saved is CourseDrafting.Outcome.Saved)
    }

    @After
    fun tearDown() = runBlocking {
        if (ProbeAccounts.skipReason != null) return@runBlocking
        ProbeAccounts.anna?.deleteMedKit(shelf.id)
        ProbeAccounts.boris?.deleteMedKit(shelf.id)
        Unit
    }

    /**
     * Сергей заявляет половину, Марина начинает лечение — и оно обеспечено только её половиной;
     * запланировать чужую нельзя; таблетка сверх плана из занятого спрашивает и без ответа не
     * пишется.
     */
    @Test
    fun marinaPlansOnlyWhatSergeyLeftFreeAndIsAskedBeforeTakingHisHalf() {
        marinaSharesTheShelf()
        sergeyJoinsAndClaimsHalf()
        marinaStartsAndIsCoveredByHerHalfOnly()
        marinaCannotPlanSergeysHalf()
        marinaIsAskedBeforeTakingFromTheReserved()
    }

    /** Полка уезжает, и пока решение в пути, звать в неё некуда — поэтому заход до кода. */
    private fun marinaSharesTheShelf() {
        openShelf(shelf.name)
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

    /**
     * Сергей входит по коду и заявляет пятьдесят таблеток — своим клиентом, по прочитанной картине
     * броней, как заявил бы его телефон (PLAN E1). Марина узнаёт об этом заходом.
     */
    private fun sergeyJoinsAndClaimsHalf() {
        val code = invitationTo(shelf.name)
        runBlocking {
            val joined = sergey.joinMedKit(MembershipPostNetworkDTO(code))
            check(joined is ApiResult.Success) { "Сергей не вошёл по коду: $joined" }
            val seen = success(sergey.packageSnapshot(box)).claims.version
            val claimed = sergey.createClaim(ClaimPostNetworkDTO(box, "50", seen))
            check(claimed is ApiResult.Success) { "Сергей не смог заявить на коробку: $claimed" }
        }
        refreshFromOptions()
    }

    /**
     * Лечение начинается, и выделение из черновика зажимается под свободное: нужно шестьдесят,
     * обеспечено пятьдесят — чужая половина в обеспечение не входит.
     */
    private fun marinaStartsAndIsCoveredByHerHalfOnly() {
        outToPlaces()
        compose.onAllNodesWithText("План").onLast().performClick()
        compose.waitUntil(WAIT) { shown("Курсы") }
        compose.onNodeWithText("Курсы").performClick()
        compose.waitUntil(WAIT) { shown("Голова Марины") }
        compose.onNodeWithText("Голова Марины").performClick()
        compose.pressAfterTyping("Начать лечение")
        compose.waitUntil(WAIT) { shownPart("обеспечено 50") }
        compose.onNodeWithText("нужно 60 приёмов · обеспечено 50", substring = true).assertExists()
    }

    /** Шестьдесят, набранные в источниках, встают на пятьдесят: из этой коробки больше не обещается. */
    private fun marinaCannotPlanSergeysHalf() {
        compose.onNodeWithText("Источники лечения").performScrollTo().performClick()
        compose.waitUntil(WAIT) { described("Приёмов из 50") }
        compose.onNode(hasContentDescription("Приёмов из 50") and hasSetTextAction()).performTextReplacement("60")
        compose.waitUntil(WAIT) {
            compose.onAllNodes(hasContentDescription("Приёмов из 50") and hasText("50")).fetchSemanticsNodes().isNotEmpty()
        }
        back()
    }

    /**
     * Свободного в коробке нет: пятьдесят — её лечение, пятьдесят — Сергея. Таблетка сверх плана
     * спрашивает, прежде чем записаться, и отказ не пишет ничего.
     */
    private fun marinaIsAskedBeforeTakingFromTheReserved() {
        openShelf(shelf.name)
        compose.waitUntil(WAIT) { shown("Ибупрофен на сотню") }
        compose.onNodeWithText("Ибупрофен на сотню").performClick()
        compose.waitUntil(WAIT) { described("Принять") }
        compose.onNodeWithContentDescription("Принять").performClick()
        compose.waitUntil(WAIT) { shown("Сколько принял") }
        compose.onNodeWithText("Сколько принял").performTextReplacement("1")
        compose.pressAfterTyping("Принять")

        compose.waitUntil(WAIT) { shown("Прежде чем записать") }
        compose.onNodeWithText("Приём заденет занятое: свободно 0", substring = true).assertExists()
        compose.onNodeWithText("Отмена").performClick()
        compose.waitUntil(WAIT) { !shown("Прежде чем записать") }

        runBlocking {
            val taken = database.intakeRepository().observeOfPackage(box).first().filterIsInstance<IntakeProjection.Unplanned>()
            assertEquals("отказ записал разовый приём", emptyList<IntakeProjection.Unplanned>(), taken)
            assertEquals(
                "коробка изменилась после отказа",
                0,
                BigDecimal("100").compareTo(requireNotNull(database.packageRepository().find(box)).quantity.amount)
            )
        }
    }

    private fun back() = compose.onNodeWithContentDescription("Назад").performClick()

    /** К местам — возвратами: панели мест в глубине нет, она у мест (PLAN H3 «Оболочка»). */
    private fun outToPlaces() {
        repeat(6) {
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

    /** Код берётся из буфера — оттуда же, откуда его возьмёт человек, чтобы переслать. */
    private fun invitationTo(name: String): String {
        openShelf(name)
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

    /** Заход целиком — очередь и снимок: там, где об обмене и говорят (PLAN H3 №28). */
    private fun refreshFromOptions() {
        outToPlaces()
        compose.onAllNodesWithText("Опции").onLast().performClick()
        compose.waitUntil(WAIT) { shown("Синхронизация") }
        compose.onAllNodesWithText("Синхронизация").onFirst().performClick()
        compose.waitUntil(WAIT) { described("Обновить") }
        compose.onNodeWithContentDescription("Обновить").performClick()
        compose.waitUntil(WAIT) { shownPart("Последний обмен в") }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun shownPart(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun described(text: String) =
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
}
