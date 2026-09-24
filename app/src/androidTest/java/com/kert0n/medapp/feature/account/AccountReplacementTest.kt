package com.kert0n.medapp.feature.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.domain.value.VocabularyLibrary
import com.kert0n.medapp.feature.bootstrap.AppStart
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.abandonment
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.transactions
import com.kert0n.medapp.network.account.AccessTokens
import com.kert0n.medapp.network.account.AccountCredentials
import com.kert0n.medapp.network.account.AccountRegistration
import com.kert0n.medapp.network.account.CredentialSource
import com.kert0n.medapp.network.account.CredentialsSaved
import com.kert0n.medapp.network.account.ServerDeviceAccount
import com.kert0n.medapp.network.account.StoredAccount
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.operation.toStorageEntity as toIntakeStorageEntity
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.time.Clock
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * «Ключ утрачен» — решение человека (PLAN G2): без него ничего не регистрируется; с ним общие полки
 * уходят утратой доступа, их очередь закрывается «отправлять некуда», брони не трогаются, местное
 * цело, и заводится новая учётка. Потерянный ответ повторяется теми же придуманными данными.
 */
@RunWith(AndroidJUnit4::class)
class AccountReplacementTest {

    private lateinit var database: MedAppDatabase
    private val consume = Uuid.random()
    private val requests = mutableListOf<String>()
    private val registrationBodies = mutableListOf<String>()

    /** Учётка в памяти: нечитаемая, пока её не стёрли. */
    private class Memory(var account: StoredAccount) : CredentialSource {
        override suspend fun read(): StoredAccount = account
        override suspend fun save(credentials: AccountCredentials): CredentialsSaved {
            account = StoredAccount.Pending(credentials)
            return CredentialsSaved.SAVED
        }
        override suspend fun confirm(): CredentialsSaved {
            (account as? StoredAccount.Pending)?.let { account = StoredAccount.Present(it.credentials) }
            return CredentialsSaved.SAVED
        }
        override suspend fun forget(): CredentialsSaved {
            account = StoredAccount.Absent
            return CredentialsSaved.SAVED
        }
    }

    private class KnownWords : VocabularyLibrary {
        override suspend fun known(): Vocabulary = VOCABULARY
        override suspend fun refresh(): Unavailability? = null
    }

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        database.medKits().upsert(
            medKit(id = SHARED_KIT, name = "Дача", publication = MedKit.Publication.PUBLISHED, participantCount = 2)
                .toMedKitStorageEntity()
        )
        database.packageRepository().add(
            pack(id = PACK, medKit = medKit(id = SHARED_KIT).ref, quantity = tablets("7"), form = TABLET_FORM)
        )
        database.packageRepository().add(pack(id = OTHER_PACK, quantity = tablets("20"), form = TABLET_FORM))
        val plan = activeCourse(sources = listOf(source(PACK, 3), source(OTHER_PACK, 5)))
        database.courseRepository().activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)))
        database.intakes().upsert(
            plannedIntake().confirm(pack(id = PACK).take(dose("2"), LATER).getOrThrow()).toIntakeStorageEntity()
        )
        // Расход по коробке общей полки ещё не уехал.
        database.syncOperations().enqueue(consume, PackageSyncCommand.Consume(PACK, dose("2"), INTAKE), LATER, medKitId = SHARED_KIT)
    }

    @After
    fun tearDown() = database.close()

    private fun replacement(stored: Memory, register: (Int) -> HttpStatusCode): AccountReplacement {
        var registrations = 0
        val api = MedAppApi(
            medAppHttpClient(
                MockEngine { request ->
                    requests += "${request.method.value} ${request.url.encodedPath}"
                    when (request.url.encodedPath) {
                        "/v1/auth/register" -> {
                            registrationBodies += request.bodyText()
                            respond("", register(registrations++), headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        "/v1/auth/token" -> respond("""{"accessToken":"t"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> respond("", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                },
                "https://medapp.test",
                retryDelay = { delayMillis(false) { 0L } }
            )
        )
        val account = ServerDeviceAccount(AccountRegistration(api, stored, "build-token", AccessTokens(stored)))
        return AccountReplacement(account, database.abandonment(), AppStart(account, KnownWords()), Clock.fixed(LATER, ZoneOffset.UTC))
    }

    private fun HttpRequestData.bodyText(): String = (body as TextContent).text

    private suspend fun operation(id: Uuid) =
        requireNotNull(database.syncOperations().find(id)).toDomain(VOCABULARY) as StoredSyncOperation.Readable

    /** Без решения приложение только называет состояние: ни одного запроса регистрации. */
    @Test
    fun keyLostWithoutADecisionRegistersNothing() = runTest {
        val stored = Memory(StoredAccount.Unreadable)
        val start = AppStart(ServerDeviceAccount(AccountRegistration(MedAppApi(medAppHttpClient(MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            respond("", HttpStatusCode.Created)
        }, "https://medapp.test")), stored, "build-token", AccessTokens(stored))), KnownWords())

        assertEquals(AppStart.Outcome.KeyLost, start.begin())

        assertEquals(emptyList<String>(), requests)
        assertEquals(StoredAccount.Unreadable, stored.account)
        assertNotNull(database.medKits().find(SHARED_KIT))
    }

    /**
     * Решение: новая учётка; местная полка, коробка, курс и приёмы на месте; общей полки нет, её
     * коробка — записью со следом, источник с неё снят; расход закрыт «отправлять некуда», и ни одной
     * команды снятия брони не поставлено. Красная проверка: убирать полку через
     * `MedKitRemoval.LeaveToOthers` — ушла бы команда `Leave`, и полка ждала бы ответа, которого не будет.
     */
    @Test
    fun theDecisionKeepsLocalRecordsAndLosesSharedShelves() = runTest {
        val stored = Memory(StoredAccount.Unreadable)

        assertEquals(AppStart.Outcome.Ready, replacement(stored) { HttpStatusCode.Created }.decide())

        assertEquals(listOf("POST /v1/auth/register"), requests)
        assertTrue(stored.account is StoredAccount.Present)
        assertNotNull(database.medKits().find(HOME_KIT))
        assertNotNull(database.packageRepository().find(OTHER_PACK))
        assertNull("общая полка осталась", database.medKits().find(SHARED_KIT))
        assertNull("коробка общей полки осталась живой", database.packageRepository().find(PACK))
        assertEquals(listOf(OTHER_PACK), database.courses().sourcePackagesOf(COURSE))
        assertNotNull(database.courses().findRecord(COURSE))
        assertEquals("Парацетамол", requireNotNull(database.intakes().find(INTAKE)).toDomain(VOCABULARY).taken?.pkg?.name)
        assertEquals(SyncOperationStatus.ACCESS_LOST, operation(consume).operation.status)
        assertEquals("в очередь поставили что-то сверх старого расхода", listOf(consume), database.syncOperations().all().map { it.operation.id })
    }

    /**
     * Ответ регистрации потерян: сервер промолчал, решение не доведено. Повтор идёт **теми же**
     * придуманными данными — сервер отвечает «занято», пропуск по ним же показывает, что учётка
     * наша; серверных полок ко второму разу уже нет, и второй раз местное не трогается.
     */
    @Test
    fun aLostAnswerIsRepeatedWithTheSameCredentials() = runTest {
        val stored = Memory(StoredAccount.Unreadable)
        val replacement = replacement(stored) { attempt -> if (attempt == 0) HttpStatusCode.BadGateway else HttpStatusCode.Conflict }

        assertEquals(AppStart.Outcome.Setup(Unavailability.SERVER_SILENT), replacement.decide())
        val invented = (stored.account as StoredAccount.Pending).credentials
        assertNull(database.medKits().find(SHARED_KIT))

        assertEquals(AppStart.Outcome.Ready, replacement.decide())

        assertEquals(StoredAccount.Present(invented), stored.account)
        assertEquals(2, registrationBodies.size)
        assertTrue("повтор другими данными", registrationBodies.all { it.contains("${invented.login}") })
        assertEquals(listOf("POST /v1/auth/register", "POST /v1/auth/register", "POST /v1/auth/token"), requests)
    }

    /** Учётка читается — решать нечего: ни стирания, ни утраты полок, приложение просто начинает работу. */
    @Test
    fun aReadableAccountIsLeftAlone() = runTest {
        val kept = AccountCredentials(Uuid.random(), "k3y-shown-only-once-43-characters-long-abcd")
        val stored = Memory(StoredAccount.Present(kept))

        assertEquals(AppStart.Outcome.Ready, replacement(stored) { HttpStatusCode.Created }.decide())

        assertEquals(StoredAccount.Present(kept), stored.account)
        assertEquals(emptyList<String>(), requests)
        assertNotNull(database.medKits().find(SHARED_KIT))
        assertNotEquals(SyncOperationStatus.ACCESS_LOST, operation(consume).operation.status)
    }
}
