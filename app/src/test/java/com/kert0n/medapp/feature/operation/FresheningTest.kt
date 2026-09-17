package com.kert0n.medapp.feature.operation

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeConnection
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.RereadingServer
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Дверь перечитывания для экранов (PLAN E4): кого она спрашивает у сервера, а кого нет. Как
 * прочитанное ложится и что сервер отвечает на самом деле — живая проба `RereadingProbe`.
 */
class FresheningTest {

    private val clock = Clock.fixed(Instant.parse("2027-03-10T12:00:00Z"), ZoneOffset.UTC)

    private val shared = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2)

    private val home = medKit(id = HOME_KIT)

    private val server = RereadingServer(clock)

    private val connection = FakeConnection(online = true)

    private val freshening = Freshening(
        server.rereading,
        connection,
        FakeMedKits(shared, home),
        FakePackages(pack(id = PACK, medKit = shared.ref), pack(id = OTHER_PACK, medKit = home.ref)),
        DirectTransactions
    )

    /** Общая полка при связи спрашивается одним запросом, и её ответ ложится. */
    @Test
    fun aSharedShelfIsAskedOnce() = runTest {
        freshening.medKit(SHARED_KIT)

        assertEquals(listOf("/v1/med-kits/$SHARED_KIT"), server.asked)
        assertEquals(1, server.laid.size)
    }

    /** Коробка общей полки — тоже одним запросом. */
    @Test
    fun aSharedBoxIsAskedOnce() = runTest {
        freshening.pack(PACK)

        assertEquals(listOf("/v1/drugs/$PACK"), server.asked)
    }

    /** Без связи не спрашивается ничего: ждать некого. */
    @Test
    fun withoutConnectionNothingIsAsked() = runTest {
        connection.online.value = false

        freshening.medKit(SHARED_KIT)
        freshening.pack(PACK)

        assertEquals(emptyList<String>(), server.asked)
    }

    /** Местную полку и её коробку сервер не знает: их 404 выдал бы пропажу на ровном месте. */
    @Test
    fun whatTheServerDoesNotKnowIsNotAsked() = runTest {
        freshening.medKit(HOME_KIT)
        freshening.pack(OTHER_PACK)
        freshening.pack(kotlin.uuid.Uuid.random())

        assertEquals(emptyList<String>(), server.asked)
    }

    /**
     * Связь порвалась на самом запросе — дверь возвращается, и ничего не легло. Сколько раз
     * клиент повторил чтение, решает он сам (`MedAppHttpClient`), а не дверь.
     */
    @Test
    fun aBrokenRequestReturnsQuietly() = runTest {
        server.reachable = false

        freshening.pack(PACK)

        assertEquals(setOf("/v1/drugs/$PACK"), server.asked.toSet())
        assertEquals(0, server.laid.size)
    }

    /** Источники лечения спрашиваются каждая своим запросом, а местные — никак. */
    @Test
    fun severalBoxesAreAskedEachAndLocalOnesAreNot() = runTest {
        freshening.packs(setOf(PACK, OTHER_PACK))

        assertEquals(listOf("/v1/drugs/$PACK"), server.asked)
    }
}
