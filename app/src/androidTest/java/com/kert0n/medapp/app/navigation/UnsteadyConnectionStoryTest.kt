package com.kert0n.medapp.app.navigation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.HiltTestActivity
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.feature.connectivity.Connection
import com.kert0n.medapp.feature.medkits.MedKitInvitation
import com.kert0n.medapp.feature.medkits.MedKitPublishing
import com.kert0n.medapp.fixture.ProbeAccounts
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.storySetting
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.pack.PackageSyncNetworkDTO
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.value.toDosageForm
import com.kert0n.medapp.network.value.toQuantityUnit
import com.kert0n.medapp.platform.connectivity.SyncTriggers
import com.kert0n.medapp.queue.QueueOutbox
import com.kert0n.medapp.queue.Synchronization
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import com.kert0n.medapp.storage.value.toStorageEntity
import com.kert0n.medapp.ui.theme.MedAppTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Связь, которая то есть, то нет** — Вера, Лида и Олег (`docs/истории.md`, U6).
 *
 * Идёт **по-настоящему**: приложение живёт пробной учёткой A, второй человек — живой клиент
 * `ProbeAccounts.boris`, механизмы доставки запущены, как в приложении (очередь и поводы захода),
 * а связь у эмулятора отнимается целиком — `svc wifi` и `svc data`: у него есть обе. «Связь
 * вернулась» слышит сама система, и никто в истории не нажимает «Обновить».
 *
 * Включается только `-Pprobe`. Связь возвращается в `@After` при любом исходе; синтетическую
 * полку удаляют оба участника.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class UnsteadyConnectionStoryTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var database: MedAppDatabase
    @Inject lateinit var packages: PackageStorageRepository
    @Inject lateinit var connection: Connection
    @Inject lateinit var outbox: QueueOutbox
    @Inject lateinit var triggers: SyncTriggers
    @Inject lateinit var synchronization: Synchronization
    @Inject lateinit var publishing: MedKitPublishing
    @Inject lateinit var invitation: MedKitInvitation

    private val WAIT = 30_000L

    /** Сколько очередь может ждать после возвращения связи, прежде чем это станет бедой истории. */
    private val DELIVERY = 60_000L

    private val shelf = medKit(id = Uuid.random(), name = "Дача")
    private val box = Uuid.random()

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
            val anna = requireNotNull(ProbeAccounts.anna)
            val units = success(anna.quantityUnits()).map { it.toQuantityUnit() }
            val forms = success(anna.formTypes()).map { it.toDosageForm() }
            database.vocabulary().save(units = units.map { it.toStorageEntity() }, forms = forms.map { it.toStorageEntity() })
            database.medKits().upsert(shelf.toStorageEntity())
            database.packageRepository().add(
                pack(id = box, name = "Парацетамол", medKit = shelf.ref, quantity = Quantity(BigDecimal("20"), units.first()), form = forms.first())
            )
            // Полка уже общая и сосед в ней — завязка, а не предмет истории: её путь проверяет история Марины.
            assertEquals(MedKitPublishing.Outcome.PUBLISHING, publishing.publish(shelf.id))
            synchronization.synchronize()
            assertEquals(MedKit.Publication.PUBLISHED, requireNotNull(database.medKits().find(shelf.id)).toDomain().publication)
            val key = (invitation.invite(shelf.id) as MedKitInvitation.Outcome.Invited).invitation.key.value
            success(requireNotNull(ProbeAccounts.boris).joinMedKit(MembershipPostNetworkDTO(key)))
        }
        // Механизмы — как в приложении: очередь едет по сигналу таблицы, связь слышит система.
        outbox.start()
        // Как в `MedApp.onCreate` — на главном потоке: наблюдатель жизни процесса иначе не ставится.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { triggers.start() }
    }

    @After
    fun tearDown() = runBlocking {
        if (ProbeAccounts.skipReason != null) return@runBlocking
        online()
        ProbeAccounts.anna?.deleteMedKit(shelf.id)
        ProbeAccounts.boris?.deleteMedKit(shelf.id)
        Unit
    }

    /** Вера зашла в погребе, поднялась к связи и спустилась снова. */
    @Test
    fun veraInTheCellar() {
        // Клиент соседа живёт в том же эмуляторе, и без связи он тоже: Глеб берёт перед тем, как
        // Вера спустилась. У Веры в базе по-прежнему двадцать — снимка с тех пор не было.
        neighbourTakes("5")
        offline()
        launch()

        openTheBox(expectWaiting = false)
        assertShows("20")
        takeFromTheSheet("2")
        assertShows("18")

        online()
        awaitServerAmount("13")
        reopenTheBox()
        assertShows("13")

        offline()
        recount("12")
        reopenTheBox()
        assertShows("12")
        assertAmountHere("12")
    }

    /** Лида ушла в тоннель посреди листа приёма и выехала из него. */
    @Test
    fun lidaInTheTunnel() {
        launch()
        openTheBox(expectWaiting = true)
        assertShows("20")
        openTheSheet()
        // Соседка берёт, пока лист открыт, — последним, что успела связь: её клиент в том же эмуляторе.
        neighbourTakes("3")
        offline()
        typeAndTake("2")
        assertShows("18")

        online()
        awaitServerAmount("15")
        reopenTheBox()
        assertShows("15")
        assertAmountHere("15")
    }

    /** Олег в горах: связь ушла и не вернулась. */
    @Test
    fun olegInTheMountains() {
        launch()
        openTheBox(expectWaiting = true)
        offline()

        takeFromTheSheet("2")
        assertShows("18")
        recount("15")
        reopenTheBox()
        assertShows("15")
        assertAmountHere("15")

        outToPlaces()
        compose.onAllNodesWithText("Опции").onLast().performClick()
        see("Синхронизация")
        compose.onAllNodesWithText("Синхронизация").onFirst().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Отправится, когда будет связь").fetchSemanticsNodes().size == 2 }
    }

    // Связь

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }

    private fun offline() = runBlocking {
        shell("svc wifi disable")
        shell("svc data disable")
        withTimeout(WAIT) { connection.online.first { !it } }
    }

    private fun online() = runBlocking {
        shell("svc wifi enable")
        shell("svc data enable")
        withTimeout(60_000) { connection.online.first { it } }
    }

    // Человек

    private fun launch() {
        compose.setContent { MedAppTheme { MedAppShell() } }
        see("Дача")
    }

    private fun openTheBox(expectWaiting: Boolean) {
        compose.onNodeWithText("Дача").performClick()
        see("Парацетамол")
        compose.onNodeWithText("Парацетамол").performClick()
        if (!expectWaiting) {
            // Без связи ждать нечего: карточка сразу со своим числом.
            compose.waitUntil(2_000) { shown("Сколько есть") }
        }
        see("Сколько есть")
    }

    private fun reopenTheBox() {
        outToPlaces()
        compose.onAllNodesWithText("Аптечки").onLast().performClick()
        see("Дача")
        openTheBox(expectWaiting = connection.online.value)
    }

    private fun openTheSheet() {
        compose.onNodeWithContentDescription("Принять").performClick()
        see("Сколько принял")
    }

    private fun typeAndTake(amount: String) {
        compose.onNodeWithText("Сколько принял").performTextInput(amount)
        compose.onAllNodesWithText("Принять").onLast().performClick()
        compose.waitUntil(WAIT) { !shown("Сколько принял") }
        see("Сколько есть")
    }

    private fun takeFromTheSheet(amount: String) {
        openTheSheet()
        typeAndTake(amount)
    }

    private fun recount(actual: String) {
        compose.onNodeWithText("Пересчитать").performClick()
        see("Пересчитал и увидел")
        compose.onNodeWithText("Пересчитал и увидел").performTextInput(actual)
        // Пока клавиатура открыта, подвала формы нет (`Form`): человек сначала убирает её.
        closeSoftKeyboard()
        compose.onNodeWithText("Записать").performClick()
        see("Сколько есть")
    }

    private fun neighbourTakes(amount: String) = runBlocking {
        val neighbour = requireNotNull(ProbeAccounts.boris)
        val seen = success(neighbour.packageSnapshot(box))
        success(neighbour.synchronise(box, Uuid.random(), PackageSyncNetworkDTO(consumed = amount, packageVersion = seen.pack.version)))
    }

    // Что видно

    /**
     * Очередь довезла сама: сервер говорит то же, что человек видит. Опрашивает клиент проверки, а не
     * приложение: его первый запрос сразу после возвращения связи может не дойти, и это «ещё не
     * видно», а не провал истории.
     */
    private fun awaitServerAmount(expected: String) = runBlocking {
        val anna = requireNotNull(ProbeAccounts.anna)
        withTimeout(DELIVERY) {
            while (true) {
                val seen = anna.packageSnapshot(box)
                if (seen is ApiResult.Success && BigDecimal(seen.value.pack.amount).compareTo(BigDecimal(expected)) == 0) break
                if (seen is ApiResult.Failure && seen.failure != ApiFailure.Unavailable) error("боевой сервер отказал: $seen")
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun assertShows(amount: String) {
        compose.waitUntil(WAIT) { shownPart("$amount ") }
    }

    /** То же число и в базе: экран не выдумал его из формы. */
    private fun assertAmountHere(expected: String) = runBlocking {
        val here = requireNotNull(packages.observe(box).first()).availability.effective.amount
        assertEquals("у человека $here, а не $expected", 0, BigDecimal(expected).compareTo(here))
    }

    private fun outToPlaces() {
        repeat(4) {
            if (shown("Опции")) return
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        }
        see("Опции")
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Ждёт слов на экране, а не дождавшись — называет, что человек видит вместо них. */
    private fun see(text: String) {
        try {
            compose.waitUntil(WAIT) { shown(text) }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("не дождались «$text»; на экране: ${screenTexts()}", timeout)
        }
    }

    private fun screenTexts(): String {
        val nodes = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
        val words = mutableListOf<String>()
        for (node in nodes) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { words += it.text }
        }
        return words.distinct().joinToString(" | ")
    }

    private fun shownPart(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
}
