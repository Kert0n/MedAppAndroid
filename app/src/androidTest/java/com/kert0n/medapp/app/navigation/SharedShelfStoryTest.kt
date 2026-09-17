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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.fixture.ProbeAccounts
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.storySetting
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.pack.PackageSyncNetworkDTO
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.network.value.toDosageForm
import com.kert0n.medapp.network.value.toQuantityUnit
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **История Марины и Сергея — полка стала общей** (`docs/истории.md`, U6).
 *
 * Идёт **по-настоящему**: приложение живёт пробной учёткой A и ведёт Марину по экранам, а Сергей —
 * второй живой клиент против того же боевого сервера (AGENTS «Связь с сервером»). Его расход не
 * подделан: он действительно берёт из общей коробки, и Марина узнаёт об этом снимком.
 *
 * Включается только `-Pprobe`. Синтетическую полку история удаляет за собой обоими участниками:
 * вышедший её удалить не может, и верить отказу на удаление нельзя — верят тому, что полки не
 * видит никто.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SharedShelfStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var database: MedAppDatabase

    private val WAIT = 30_000L
    private val shelf = medKit(id = Uuid.random(), name = "Семейная Марины")
    private val box = Uuid.random()
    private lateinit var unit: QuantityUnit
    private lateinit var form: DosageForm

    private fun <T> success(result: ApiResult<T>): T = when (result) {
        is ApiResult.Success -> result.value
        is ApiResult.Failure -> error("боевой сервер отказал: $result")
    }

    @Before
    fun setUp() {
        val why = ProbeAccounts.skipReason
        assumeTrue(why.orEmpty(), why == null)
        hilt.inject()
        runBlocking {
            database.storySetting()
            // Словарь берётся у самого сервера: выдуманных единиц он не знает и коробку с ними не
            // принимает. Это и есть разница между историей на подделке и историей живьём.
            val anna = requireNotNull(ProbeAccounts.anna)
            val units = success(anna.quantityUnits()).map { it.toQuantityUnit() }
            val forms = success(anna.formTypes()).map { it.toDosageForm() }
            database.vocabulary().save(
                units = units.map { it.toStorageEntity() },
                forms = forms.map { it.toStorageEntity() }
            )
            unit = units.first()
            form = forms.first()
            database.medKits().upsert(shelf.toStorageEntity())
            database.packageRepository().add(
                pack(
                    id = box, name = "Ибупрофен Марины", medKit = shelf.ref,
                    quantity = Quantity(BigDecimal("10"), unit), form = form
                )
            )
        }
        compose.setContent { MedAppTheme { MedAppShell() } }
    }

    @After
    fun tearDown() = runBlocking {
        if (ProbeAccounts.skipReason != null) return@runBlocking
        ProbeAccounts.anna?.deleteMedKit(shelf.id)
        ProbeAccounts.boris?.deleteMedKit(shelf.id)
        Unit
    }

    /**
     * Марина открывает полку соседу, Сергей входит по коду и берёт две таблетки, Марина видит
     * новое число.
     *
     * Стережёт: цена решения читается **до** кнопки; пока решение едет, звать некуда; код годен
     * на самом деле — по нему входит живой человек; чужой расход приходит снимком и меняет число,
     * а не лечение.
     */
    @Test
    fun marinaOpensTheShelfAndSergeyTakesFromIt() {
        marinaReadsThePriceAndMakesItShared()
        val code = marinaTakesTheCode()
        sergeyJoinsAndTakesTwo(code)
        marinaSeesTheNewNumber()
        sergeyEmptiesTheBoxBehindHerBack()
        marinaTakesWhatIsNoLongerThereAndIsRefused()
    }

    private fun marinaReadsThePriceAndMakesItShared() {
        compose.waitUntil(WAIT) { shown("Семейная Марины") }
        compose.onNodeWithText("Семейная Марины").performClick()
        compose.waitUntil(WAIT) { shown("Ибупрофен Марины") }
        compose.onNodeWithContentDescription("Что сделать с аптечкой").performClick()
        compose.onNodeWithText("Поделиться аптечкой").performClick()

        compose.waitUntil(WAIT) { shown("Станет общим") }
        compose.onNodeWithText("Останется у вас").assertIsDisplayed()
        compose.onNodeWithText(
            "Это необратимо: переданный доступ нельзя отозвать. Любой участник сможет удалить аптечку у всех."
        ).assertIsDisplayed()

        compose.onNodeWithText("Сделать общей").performClick()
        compose.onAllNodesWithText("Сделать общей").onLast().performClick()
    }

    private fun marinaTakesTheCode(): String {
        // Половины полки не бывает: пока решение едет, приглашений она не выдаёт — и Марина
        // видит это словами, а не пустым экраном.
        compose.waitUntil(WAIT) { shown("Решение об аптечке уже едет серверу. Пригласить можно будет, когда оно доедет.") }
        // Ждать фонового захода она не стала и обновила сама — оттуда, где об обмене и говорят.
        refreshFromOptions()
        openTheShelf()
        compose.onNodeWithContentDescription("Что сделать с аптечкой").performClick()
        compose.onNodeWithText("Пригласить").performClick()
        compose.waitUntil(WAIT) { shown("Пригласить") }
        compose.onNodeWithText("Пригласить").performClick()
        compose.onAllNodesWithText("Пригласить").onLast().performClick()
        compose.waitUntil(WAIT) { shown("Код скопирован — его можно переслать") }
        // Код берётся из буфера — оттуда же, откуда его возьмёт человек, чтобы переслать соседу.
        var code: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            code = compose.activity.getSystemService(ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.text?.toString()
        }
        return requireNotNull(code?.takeIf { it.isNotBlank() }) { "код приглашения не лёг в буфер" }
    }

    private fun sergeyJoinsAndTakesTwo(code: String) = runBlocking {
        val sergey = requireNotNull(ProbeAccounts.boris)
        val joined = sergey.joinMedKit(MembershipPostNetworkDTO(code))
        check(joined is ApiResult.Success) { "Сергей не вошёл по коду: $joined" }
        // Читает перед тем, как писать: запрос готовится по прочитанному состоянию (PLAN E1).
        val seen = success(sergey.packageSnapshot(box))
        val taken = sergey.synchronise(
            box, Uuid.random(), PackageSyncNetworkDTO(consumed = "2", packageVersion = seen.pack.version)
        )
        check(taken is ApiResult.Success) { "Сергей не смог взять из коробки: $taken" }
    }

    private fun marinaSeesTheNewNumber() {
        refreshFromOptions()
        openTheShelf()
        // Число стало другим: сколько именно, зависит от единицы боевого словаря, поэтому
        // сверяется само число, а не вся строка.
        compose.waitUntil(WAIT) { shownPart("8") }
    }

    /** Сергей допивает коробку, пока Марина о ней не спрашивала: она думает, что там восемь. */
    private fun sergeyEmptiesTheBoxBehindHerBack() = runBlocking {
        val sergey = requireNotNull(ProbeAccounts.boris)
        val seen = success(sergey.packageSnapshot(box))
        val taken = sergey.synchronise(
            box, Uuid.random(), PackageSyncNetworkDTO(consumed = "7", packageVersion = seen.pack.version)
        )
        check(taken is ApiResult.Success) { "Сергей не смог допить коробку: $taken" }
    }

    /**
     * Марина принимает три таблетки из коробки, в которой их уже одна. Отказ здесь **заработан**:
     * сервер отвергает её расход по остатку сам, а не по заказу проверки. Экран называет причину
     * и ведёт к пересчёту — расхождение о числе лечится им (PLAN E3, REQ-045).
     */
    private fun marinaTakesWhatIsNoLongerThereAndIsRefused() {
        openTheShelf()
        compose.onNodeWithText("Ибупрофен Марины").performClick()
        compose.waitUntil(WAIT) { shown("Сколько есть") }
        compose.onNodeWithContentDescription("Принять").performClick()
        compose.waitUntil(WAIT) { shown("Принять разово") }
        compose.onNodeWithText("Сколько принял").performTextInput("3")
        compose.onAllNodesWithText("Принять").onLast().performClick()
        compose.waitUntil(WAIT) { !shown("Принять разово") }

        refreshFromOptions()

        compose.waitUntil(WAIT) { shown("На сервере осталось меньше, чем вы списали") }
        compose.onNodeWithText("Пересчитать коробку").assertIsDisplayed()
    }

    /** К местам — возвратами: панели мест в глубине нет, она у мест (PLAN H3 «Оболочка»). */
    private fun outToPlaces() {
        repeat(4) {
            if (shown("Опции")) return
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        }
        compose.waitUntil(WAIT) { shown("Опции") }
    }

    private fun openTheShelf() {
        outToPlaces()
        // «Аптечки» на экране бывает двумя узлами — подписью места и заголовком самого места,
        // поэтому нажимается подпись внизу, а не первое совпадение.
        if (!shown("Семейная Марины")) {
            compose.onAllNodesWithText("Аптечки").onLast().performClick()
        }
        compose.waitUntil(WAIT) { shown("Семейная Марины") }
        compose.onNodeWithText("Семейная Марины").performClick()
        compose.waitUntil(WAIT) { shown("Ибупрофен Марины") }
    }

    /** Заход целиком — очередь и снимок: там, где об обмене и говорят (PLAN H3 №28). */
    private fun refreshFromOptions() {
        outToPlaces()
        compose.onAllNodesWithText("Опции").onLast().performClick()
        compose.waitUntil(WAIT) { shown("Синхронизация") }
        compose.onAllNodesWithText("Синхронизация").onFirst().performClick()
        // «Обновить» — подпись значка, а не текст: у кнопки в верхней панели слов нет.
        compose.waitUntil(WAIT) { described("Обновить") }
        compose.onNodeWithContentDescription("Обновить").performClick()
        // Ждём конца захода, а не пустой очереди: отвергнутая строка из неё и не должна уходить —
        // она ждёт решения человека (PLAN C1 «Отказ разобран человеком»).
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
