package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitInvitations
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.feature.medkits.MedKitInvitation
import com.kert0n.medapp.feature.medkits.MedKitPublishing
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeCourses
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.HeldTransactions
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.register.MedAppRegister
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.ServerKnowledge
import com.kert0n.medapp.queue.ServerSnapshot
import com.kert0n.medapp.queue.SnapshotApplier
import com.kert0n.medapp.queue.SnapshotStorage
import com.kert0n.medapp.queue.Transactions
import io.ktor.client.engine.mock.MockEngine
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Поделиться аптечкой (PLAN H3 №20): что человек видит и что записывается. Как это нарисовано,
 * проверяет `MedKitSharingScreenTest`.
 *
 * Лиц у экрана два, и выбирает между ними полка, а не второй ключ маршрута, — поэтому почти
 * каждая проверка здесь начинается с того, какая полка открыта.
 */
class MedKitSharingViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-16T11:30:00Z"), ZoneId.of("Europe/Moscow"))

    private val medKits = FakeMedKits(
        medKit(id = HOME_KIT, name = "Домашняя"),
        medKit(id = SHARED_KIT, name = "Семейная", publication = MedKit.Publication.PUBLISHED, participantCount = 3)
    )

    private val packages = FakePackages(pack(medKit = medKit(id = HOME_KIT).ref))

    private val queue = QueueService(DirectTransactions, FakeQueue())

    /** Ключ, который выдаёт сервер; `issue` отвечает тем, что положили сюда. */
    private var issue: MedKitInvitations.Issue = MedKitInvitations.Issue.Issued(InvitationKey("K7F-2M9-QX4"))

    private val invitations = object : MedKitInvitations {
        var asked = 0
        override suspend fun issue(medKit: MedKit): MedKitInvitations.Issue {
            asked++
            return issue
        }
    }

    private fun viewModel(medKitId: Uuid = HOME_KIT, transactions: Transactions = DirectTransactions) =
        MedKitSharingViewModel(
            publishing = MedKitPublishing(
                medKits = medKits,
                packages = packages,
                relocation = PackageRelocation(packages, medKits, FakeCourses(), queue, DirectTransactions, clock),
                queue = queue,
                transactions = transactions,
                clock = clock
            ),
            invitations = MedKitInvitation(medKits, invitations, snapshots(), Duration.ofHours(1), clock),
            medKits = medKits,
            today = Today(clock, QuietClock),
            medKitId = medKitId
        )

    /**
     * Снимок сценарию нужен только в одном случае — сервер сказал «полки не видно», — и ни одна
     * проверка здесь в него не заходит. Спросят — упадёт, и это лучше, чем тихий пустой ответ
     * (разбор #51 «Заглушки портов падают сразу»).
     */
    private fun snapshots(): SnapshotApplier {
        val api = MedAppApi(medAppHttpClient(MockEngine { error("снимок этой проверке не нужен") }, "https://medapp.test"))
        val vocabulary = VocabularyResolver(FakeVocabulary(), api)
        return SnapshotApplier(MedAppRegister(api, PackageSnapshotResolver(vocabulary, FakeQueue()), clock), Untouched, clock)
    }

    private object Untouched : SnapshotStorage {
        override suspend fun serverKnows(): ServerKnowledge = error("снимок этой проверке не нужен")
        override suspend fun lay(snapshot: ServerSnapshot, at: Instant): Unit = error("снимок этой проверке не нужен")
    }

    /** Первым — цена решения: до нажатия человек читает, что станет общим. */
    @Test
    fun aLocalShelfIsOfferedTheDecision() {
        val model = viewModel()

        val state = watching(model.state) { it.awaiting { s -> s !is MedKitSharingUiState.Loading } }

        assertEquals("Домашняя", (state as MedKitSharingUiState.Deciding).name)
    }

    /**
     * Подтверждение спрашивается **до** сценария (PLAN H3 «Подтверждения опасных действий»):
     * доступ необратим, и «сделать общей» нажимают дважды осознанно.
     */
    @Test
    fun theQuestionComesBeforeTheDecision() {
        val model = viewModel()

        val asked = watching(model.state) { state ->
            model.ask()
            state.awaiting { it is MedKitSharingUiState.Deciding && it.isAsking }
        }

        assertTrue((asked as MedKitSharingUiState.Deciding).isAsking)
        assertEquals(MedKit.Publication.LOCAL, medKits.medKits.single { it.id == HOME_KIT }.publication)
    }

    /** Второе нажатие, пока идёт первое, публикует ту же полку один раз, а не дважды. */
    @Test
    fun pressingTwiceSharesOnce() {
        val transactions = HeldTransactions()
        val model = viewModel(transactions = transactions)

        watching(model.state) { state ->
            model.ask()
            model.publish()
            model.publish()
            transactions.door.release()
            state.awaiting { it is MedKitSharingUiState.OnItsWay }
        }

        assertEquals(1, transactions.door.waiting)
    }

    /**
     * Половины полки не бывает: пока решение едет, звать некуда, и кнопки приглашения нет
     * (PLAN D2, E5).
     */
    @Test
    fun aShelfOnItsWayInvitesNobody() {
        runBlocking { medKits.mark(HOME_KIT, MedKitStatus.PUBLISHING) }
        val model = viewModel()

        val state = watching(model.state) { it.awaiting { s -> s !is MedKitSharingUiState.Loading } }

        assertTrue(state is MedKitSharingUiState.OnItsWay)
    }

    /** У общей полки лицо второе: ключ выдают и показывают вместе с оценкой срока. */
    @Test
    fun aSharedShelfHandsOutACodeWithAnEstimatedTerm() {
        val model = viewModel(medKitId = SHARED_KIT)

        val state = watching(model.state) { state ->
            model.ask()
            model.invite()
            state.awaiting { it is MedKitSharingUiState.Shared && it.invitation != null }
        }

        val invitation = (state as MedKitSharingUiState.Shared).invitation!!
        assertEquals(InvitationKey("K7F-2M9-QX4"), invitation.key)
        // Час местный: приглашение выдано в 11:30 UTC, срок — час, Москва на три часа впереди.
        assertEquals(LocalTime.of(15, 30), invitation.expiresAround)
    }

    /**
     * Сервера нет — причина названа, а экран работает: человек повторяет, а не остаётся с пустым
     * местом, о котором нечего сказать.
     */
    @Test
    fun aRefusedInvitationIsExplainedAndTheScreenKeepsWorking() {
        issue = MedKitInvitations.Issue.Unavailable(Unavailability.NO_CONNECTION)
        val model = viewModel(medKitId = SHARED_KIT)

        val state = watching(model.state) { state ->
            model.invite()
            state.awaiting { it is MedKitSharingUiState.Shared && it.refusal != null }
        }

        val shared = state as MedKitSharingUiState.Shared
        assertEquals(MedKitSharingRefusal.Unavailable(Unavailability.NO_CONNECTION), shared.refusal)
        assertNull(shared.invitation)
    }

    /** Полки нет — делиться нечем, и экран говорит это, а не показывает пустое решение. */
    @Test
    fun aShelfThatIsGoneSaysSo() {
        val model = viewModel(medKitId = Uuid.random())

        val state = watching(model.state) { it.awaiting { s -> s !is MedKitSharingUiState.Loading } }

        assertEquals(MedKitSharingUiState.Gone, state)
    }

    /** Полного экрана без ключа не бывает: показывать там нечего. */
    @Test
    fun thereIsNoFullScreenWithoutACode() {
        val model = viewModel(medKitId = SHARED_KIT)

        val state = watching(model.state) { state ->
            model.showFullScreen()
            state.awaiting { it is MedKitSharingUiState.Shared }
        }

        assertTrue(!(state as MedKitSharingUiState.Shared).isFullScreen)
    }

    /**
     * «Обновить код» с полного экрана оставляет человека там же: он нажал, не закрывая, и ждёт
     * новый код на том же месте.
     */
    @Test
    fun refreshingTheCodeKeepsTheFullScreenOpen() {
        val model = viewModel(medKitId = SHARED_KIT)

        val state = watching(model.state) { state ->
            model.invite()
            state.awaiting { it is MedKitSharingUiState.Shared && it.invitation != null }
            model.showFullScreen()
            state.awaiting { it is MedKitSharingUiState.Shared && it.isFullScreen }
            issue = MedKitInvitations.Issue.Issued(InvitationKey("Z1A-4B7-QW2"))
            model.invite()
            state.awaiting { it is MedKitSharingUiState.Shared && it.invitation?.key == InvitationKey("Z1A-4B7-QW2") }
        }

        assertTrue((state as MedKitSharingUiState.Shared).isFullScreen)
    }

    /**
     * Ключ не переживает смерть процесса — и это честно: в маршрут он не едет (G3), а сервер
     * сроку не обещает и мог забыть его раньше (B6). Человек видит полку общей и «Пригласить», а
     * не пустой узор, который камера всё равно не прочитает.
     */
    @Test
    fun theCodeDoesNotSurviveTheProcessAndTheScreenOffersANewOne() {
        val first = viewModel(medKitId = SHARED_KIT)
        watching(first.state) { state ->
            first.invite()
            state.awaiting { it is MedKitSharingUiState.Shared && it.invitation != null }
        }

        // Заново поднятый экран — то же самое, что после смерти процесса.
        val afterDeath = viewModel(medKitId = SHARED_KIT)
        val state = watching(afterDeath.state) { it.awaiting { s -> s is MedKitSharingUiState.Shared } }

        val shared = state as MedKitSharingUiState.Shared
        assertNull(shared.invitation)
        assertTrue(!shared.isFullScreen)
    }
}
