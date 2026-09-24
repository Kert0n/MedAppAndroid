package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.domain.scan.CodeFormat
import com.kert0n.medapp.domain.scan.ScannedCode
import com.kert0n.medapp.feature.medkits.MedKitJoining
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.register.MedAppRegister
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.queue.ServerKnowledge
import com.kert0n.medapp.queue.ServerSnapshot
import com.kert0n.medapp.queue.SnapshotApplier
import com.kert0n.medapp.queue.SnapshotStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Присоединиться к чужой аптечке (PLAN H3 №22): что уходит на сервер и что человек видит в ответ.
 * Как это нарисовано, проверяет `MedKitJoiningScreenTest`.
 */
class MedKitJoiningViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    /**
     * Сценарий настоящий: своей логики у него нет — он передаёт ключ снимку, и весь его разбор
     * это ответ сервера. Поэтому случаи задаются кодом ответа, а не подменой сценария.
     */
    private inner class Server(private val answer: HttpStatusCode, private val body: String = "") {
        var asked = 0
        val held = CompletableDeferred<Unit>()
        var holds = false

        val joining: MedKitJoining by lazy {
            val api = MedAppApi(
                medAppHttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("/med-kit-memberships")) {
                            asked++
                            if (holds) held.await()
                            if (answer == HttpStatusCode.ServiceUnavailable) throw IOException("нет связи")
                            respond(body, answer, headersOf(HttpHeaders.ContentType, "application/json"))
                        } else {
                            respond(snapshotJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    },
                    "https://medapp.test",
                    retryDelay = { delayMillis(false) { 0L } }
                )
            )
            val vocabulary = VocabularyResolver(FakeVocabulary(), api)
            MedKitJoining(
                SnapshotApplier(MedAppRegister(api, PackageSnapshotResolver(vocabulary, FakeQueue()), clock), Laid(), clock)
            )
        }
    }

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneOffset.UTC)

    private fun shelfJson(id: Uuid) = """{"id":"$id","userCount":2,"drugs":[]}"""

    private fun snapshotJson() = """{"id":"${Uuid.random()}","medKits":[${shelfJson(SHARED_KIT)}]}"""

    private class Laid : SnapshotStorage {
        override suspend fun serverKnows() = ServerKnowledge(emptySet(), emptySet(), emptySet(), emptySet())
        override suspend fun lay(snapshot: ServerSnapshot, at: Instant) = Unit
    }

    private fun viewModel(server: Server) = MedKitJoiningViewModel(server.joining)

    /** Пробелы вокруг кода — не часть его: код приходит перепиской и вставляется вместе с ними. */
    @Test
    fun spacesAroundTheCodeAreNotPartOfIt() {
        assertEquals(
            InvitationKey("K7F-2M9-QX4"),
            (MedKitJoiningUiState(code = "  K7F-2M9-QX4 ").parsed() as ParsedInput.Parsed).value
        )
    }

    /** Пустой код домену не показывается: нажимать было не с чем, и это сказано у поля. */
    @Test
    fun anEmptyCodeIsRefusedAtItsField() {
        val server = Server(HttpStatusCode.Conflict)
        val model = viewModel(server)

        val state = watching(model.state) { state ->
            model.type("   ")
            model.join()
            state.awaiting { it.refusal != null }
        }

        assertEquals(MedKitJoiningRefusal.Empty, state.refusal)
        assertEquals(0, server.asked)
    }

    /**
     * Узнанный QR кладёт ключ в поле и сразу зовёт вступление: человек наводил телефон ради этого,
     * и второе нажатие было бы просьбой подтвердить то, что он уже сделал.
     */
    @Test
    fun aScannedInvitationJoinsRightAway() {
        val server = Server(HttpStatusCode.Created, shelfJson(SHARED_KIT))
        val model = viewModel(server)

        val state = watching(model.state) { state ->
            model.scan()
            model.seen(ScannedCode(CodeFormat.QR, "K7F-2M9-QX4"))
            state.awaiting { it.joined != null }
        }

        assertEquals("K7F-2M9-QX4", state.code)
        assertEquals(1, server.asked)
        assertEquals(false, state.isScanning)
    }

    /**
     * Код с упаковки лекарства приглашением не станет: камера ждёт другого кадра, а на сервер
     * заведомо чужая строка не уходит.
     */
    @Test
    fun aPackageCodeIsNotAnInvitation() {
        val server = Server(HttpStatusCode.Created, shelfJson(SHARED_KIT))
        val model = viewModel(server)

        watching(model.state) { state ->
            model.scan()
            model.seen(ScannedCode(CodeFormat.DATA_MATRIX, "010460123456789021512345"))
            state.awaiting { it.isScanning }
        }

        assertEquals("", model.state.value.code)
        assertEquals(0, server.asked)
    }

    /**
     * Камеру не дали — сказано словами. Молчание здесь неотличимо от сломанной кнопки: человек
     * жмёт «Отсканировать», и ничего не происходит; при этом поле кода и вступление остаются
     * на месте — код вводят руками (разбор #55).
     */
    @Test
    fun aDeniedCameraIsSaidInWordsAndTypingStays() {
        val server = Server(HttpStatusCode.Created, shelfJson(SHARED_KIT))
        val model = viewModel(server)

        watching(model.state) { state ->
            model.type("K7F-2M9")
            model.scan()
            model.cameraDenied()
            state.awaiting { it.refusal != null }
        }

        assertEquals(MedKitJoiningRefusal.CameraDenied, model.state.value.refusal)
        assertEquals(false, model.state.value.isScanning)
        assertEquals("K7F-2M9", model.state.value.code)
    }

    /**
     * Кадр, пришедший в очереди после узнанного кода, не подменяет код, по которому вошли, и
     * второго вступления не зовёт: иначе ответ на первый код лёг бы под вторым.
     */
    @Test
    fun aLateFrameDoesNotReplaceTheCodeBeingJoined() {
        val server = Server(HttpStatusCode.Created, shelfJson(SHARED_KIT))
        val model = viewModel(server)

        watching(model.state) { state ->
            model.scan()
            model.seen(ScannedCode(CodeFormat.QR, "K7F-2M9-QX4"))
            model.seen(ScannedCode(CodeFormat.QR, "ZZZ-000-ZZZ"))
            model.scan()
            state.awaiting { it.joined != null }
        }
        model.seen(ScannedCode(CodeFormat.QR, "ZZZ-000-ZZZ"))

        assertEquals("K7F-2M9-QX4", model.state.value.code)
        assertEquals(false, model.state.value.isScanning)
        assertEquals(1, server.asked)
    }

    /** Вошёл — экран называет полку, и оболочка ведёт в неё. */
    @Test
    fun joiningNamesTheShelfToGoTo() {
        val model = viewModel(Server(HttpStatusCode.Created, shelfJson(SHARED_KIT)))

        val state = watching(model.state) { state ->
            model.type("K7F-2M9-QX4")
            model.join()
            state.awaiting { it.joined != null }
        }

        assertEquals(SHARED_KIT, state.joined)
    }

    /**
     * Негодное приглашение — **одна фраза**: неизвестный ключ, истёкший и выход пригласившего
     * сервер не различает (PLAN B6).
     */
    @Test
    fun aBadInvitationIsOnePhrase() {
        val model = viewModel(Server(HttpStatusCode.NotFound))

        val state = watching(model.state) { state ->
            model.type("K7F-2M9-QX4")
            model.join()
            state.awaiting { it.refusal != null }
        }

        assertEquals(MedKitJoiningRefusal.Invalid, state.refusal)
        assertNull(state.joined)
    }

    /**
     * Связь оборвалась — исход **неизвестен**: сервер мог вступить, а ответ не дойти. Экран так и
     * говорит и зовёт повторить; повтор тем же кодом ответит «вы уже в этой аптечке», а полку
     * принесёт снимок (PLAN E4).
     */
    @Test
    fun aLostAnswerIsNamedAndRetried() {
        val model = viewModel(Server(HttpStatusCode.ServiceUnavailable))

        val state = watching(model.state) { state ->
            model.type("K7F-2M9-QX4")
            model.join()
            state.awaiting { it.refusal != null }
        }

        assertEquals(MedKitJoiningRefusal.Unavailable(Unavailability.SERVER_SILENT), state.refusal)
        assertNull(state.joined)
    }

    /** Второе нажатие, пока идёт первое, входит один раз, а не дважды. */
    @Test
    fun pressingTwiceJoinsOnce() {
        val server = Server(HttpStatusCode.Created, shelfJson(SHARED_KIT)).also { it.holds = true }
        val model = viewModel(server)

        watching(model.state) { state ->
            model.type("K7F-2M9-QX4")
            model.join()
            state.awaiting { it.isWorking }
            model.join()
            server.held.complete(Unit)
            state.awaiting { it.joined != null }
        }

        assertEquals(1, server.asked)
    }

    /** Ввод снимает отказ: человек уже правит то, на что ему указали. */
    @Test
    fun typingClearsTheRefusal() {
        val model = viewModel(Server(HttpStatusCode.NotFound))

        val state = watching(model.state) { state ->
            model.type("K7F-2M9-QX4")
            model.join()
            state.awaiting { it.refusal != null }
            model.type("Z1A-4B7-QW2")
            state.awaiting { it.refusal == null }
        }

        assertTrue(state.refusal == null)
    }
}
