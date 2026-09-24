package com.kert0n.medapp.feature.medkits

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitInvitations
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.snapshotStorage
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.register.MedAppRegister
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.queue.SnapshotApplier
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.VocabularyRoomRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Звать можно только в полку, которая уехала целиком: местной на сервере нет, а публикуемая ещё без
 * части коробок (PLAN D2, E5). Срок ключа — оценка от момента выдачи (B6).
 */
@RunWith(AndroidJUnit4::class)
class MedKitInvitationTest {

    private lateinit var database: MedAppDatabase
    private val clock: Clock = Clock.fixed(LATER, ZoneOffset.UTC)
    private val term: Duration = Duration.ofMinutes(60)

    /** Что ответит сервер на выдачу; сколько раз спрашивали. */
    private class Server(var issue: MedKitInvitations.Issue) : MedKitInvitations {
        var asked = 0
        override suspend fun issue(medKit: MedKit): MedKitInvitations.Issue {
            asked++
            return issue
        }
    }

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() = database.close()

    /** Полный снимок отвечает, что у нас нет ни одной полки; `online = false` — снимок не прочитать. */
    private fun invitation(server: Server, online: Boolean = true): MedKitInvitation {
        val api = MedAppApi(
            medAppHttpClient(
                MockEngine {
                    if (!online) throw java.io.IOException("нет связи")
                    respond("""{"id":"${Uuid.random()}","medKits":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                "https://medapp.test",
                retryDelay = { delayMillis(false) { 0L } }
            )
        )
        val vocabulary = VocabularyResolver(VocabularyRoomRepository(database.vocabulary()), api)
        val snapshots = SnapshotApplier(MedAppRegister(api, PackageSnapshotResolver(vocabulary, database.queueStorage()), clock), database.snapshotStorage(), clock)
        return MedKitInvitation(database.medKitRepository(), server, snapshots, term, clock)
    }

    private suspend fun published(id: Uuid) =
        database.medKits().upsert(medKit(id = id, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity())

    /** Уехавшая полка: ключ и оценка срока от момента выдачи. */
    @Test
    fun aShelfThatWentWhollyIsInvitedInto() = runTest {
        published(SHARED_KIT)
        val server = Server(MedKitInvitations.Issue.Issued(InvitationKey("ключ")))

        val outcome = invitation(server).invite(SHARED_KIT) as MedKitInvitation.Outcome.Invited

        assertEquals(InvitationKey("ключ"), outcome.invitation.key)
        assertEquals(LATER.plus(term), outcome.invitation.expiresAround)
    }

    /** Местная полка приглашений не выдаёт, и сервер об этом не спрашивают: там её нет. */
    @Test
    fun aLocalShelfIsNotShared() = runTest {
        val server = Server(MedKitInvitations.Issue.Issued(InvitationKey("ключ")))

        assertEquals(MedKitInvitation.Outcome.NotShared, invitation(server).invite(HOME_KIT))
        assertEquals(0, server.asked)
    }

    /** Полка уезжает, а коробки ещё нет: половину полки не показывают — подождать (PLAN D2). */
    @Test
    fun aShelfBeingPublishedIsNotInvitedIntoYet() = runTest {
        val server = Server(MedKitInvitations.Issue.Issued(InvitationKey("ключ")))
        Scenarios(database, LATER).medKitPublishing.publish(HOME_KIT)

        assertEquals(MedKitInvitation.Outcome.Busy, invitation(server).invite(HOME_KIT))
        assertEquals(0, server.asked)
    }

    /**
     * Сервер полки нам не показывает — её убрали, пока мы не смотрели. Снимок читается сразу, и полка
     * уходит из списка тут же, а не при следующем фоновом заходе (PLAN E4).
     */
    @Test
    fun aShelfTheServerNoLongerShowsIsGoneAtOnce() = runTest {
        published(SHARED_KIT)

        val outcome = invitation(Server(MedKitInvitations.Issue.NotAccessible)).invite(SHARED_KIT)

        assertEquals(MedKitInvitation.Outcome.MedKitGone, outcome)
        assertNull(database.medKits().find(SHARED_KIT))
    }

    /** Снимок не прочитался — полка ещё в списке, и «её нет» экран не скажет: сервера нет. */
    @Test
    fun aShelfTheServerNoLongerShowsStaysUntilTheSnapshotIsRead() = runTest {
        published(SHARED_KIT)

        val outcome = invitation(Server(MedKitInvitations.Issue.NotAccessible), online = false).invite(SHARED_KIT)

        assertEquals(MedKitInvitation.Outcome.Unavailable(Unavailability.NO_CONNECTION), outcome)
        assertEquals(SHARED_KIT, database.medKits().find(SHARED_KIT)?.id)
    }

    @Test
    fun noServerNamesTheReason() = runTest {
        published(SHARED_KIT)

        val outcome = invitation(Server(MedKitInvitations.Issue.Unavailable(Unavailability.NO_CONNECTION))).invite(SHARED_KIT)

        assertEquals(MedKitInvitation.Outcome.Unavailable(Unavailability.NO_CONNECTION), outcome)
    }
}
