package com.kert0n.medapp.feature.operation

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeConnection
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.RereadingServer
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Дверь перечитывания для экранов (PLAN E4): кого она спрашивает у сервера, а кого нет. Как
 * прочитанное ложится и что сервер отвечает на самом деле — живая проба `RereadingProbe`.
 */
class FresheningTest {

    /** Часы, которые проверка двигает сама: окно свежести меряется ими, а не ожиданием. */
    private class TestClock(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = TestClock(Instant.parse("2027-03-10T12:00:00Z"))

    /** Область приложения: чтение живёт в ней, а не у того, кто его ждёт. */
    private val application = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val shared = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2)

    private val home = medKit(id = HOME_KIT)

    private val server = RereadingServer(clock)

    private val connection = FakeConnection(online = true)

    private val freshening = Freshening(
        server.rereading,
        connection,
        FakePackages(pack(id = PACK, medKit = shared.ref), pack(id = OTHER_PACK, medKit = home.ref)),
        DirectTransactions,
        clock,
        application
    )

    @After
    fun close() = application.cancel()

    /** Ждать приходится по настоящим часам: чтение идёт в области приложения, на своих потоках. */
    private suspend fun until(what: String, condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }.also { check(condition()) { what } }

    /** Список полок при связи спрашивается одним запросом, и его ответ ложится. */
    @Test
    fun theShelfListIsAskedOnce() = runBlocking {
        freshening.medKits()

        assertEquals(listOf("/v1/med-kits"), server.asked)
        assertEquals(1, server.laid.size)
    }

    /** Коробка общей полки — тоже одним запросом. */
    @Test
    fun aSharedBoxIsAskedOnce() = runBlocking {
        freshening.pack(PACK)

        assertEquals(listOf("/v1/drugs/$PACK"), server.asked)
    }

    /** Без связи не спрашивается ничего: ждать некого. */
    @Test
    fun withoutConnectionNothingIsAsked() = runBlocking {
        connection.online.value = false

        freshening.medKits()
        freshening.pack(PACK)

        assertEquals(emptyList<String>(), server.asked)
    }

    /** Коробку местной полки и незаведённую сервер не знает: их 404 выдал бы пропажу на ровном месте. */
    @Test
    fun whatTheServerDoesNotKnowIsNotAsked() = runBlocking {
        freshening.pack(OTHER_PACK)
        freshening.pack(kotlin.uuid.Uuid.random())

        assertEquals(emptyList<String>(), server.asked)
    }

    /**
     * Связь порвалась на самом запросе — дверь возвращается, и ничего не легло. Сколько раз
     * клиент повторил чтение, решает он сам (`MedAppHttpClient`), а не дверь.
     */
    @Test
    fun aBrokenRequestReturnsQuietly() = runBlocking {
        server.reachable = false

        freshening.pack(PACK)

        assertEquals(setOf("/v1/drugs/$PACK"), server.asked.toSet())
        assertEquals(0, server.laid.size)
    }

    /** Источники лечения спрашиваются каждая своим запросом, а местные — никак. */
    @Test
    fun severalBoxesAreAskedEachAndLocalOnesAreNot() = runBlocking {
        freshening.packs(setOf(PACK, OTHER_PACK))

        assertEquals(listOf("/v1/drugs/$PACK"), server.asked)
    }

    /** Прочитанное меньше 30 секунд назад не спрашивается снова; на тридцатой секунде — снова. */
    @Test
    fun whatWasReadWithinHalfAMinuteIsNotAskedAgain() = runBlocking {
        freshening.pack(PACK)
        clock.now = clock.now.plus(Duration.ofSeconds(29))
        freshening.pack(PACK)

        assertEquals(listOf("/v1/drugs/$PACK"), server.asked)

        clock.now = clock.now.plus(Duration.ofSeconds(1))
        freshening.pack(PACK)

        assertEquals(2, server.asked.size)
    }

    /** Список и коробка — разные вещи: прочитанный список коробку свежей не делает. */
    @Test
    fun theListDoesNotFreshenABox() = runBlocking {
        freshening.medKits()
        freshening.pack(PACK)

        assertEquals(listOf("/v1/med-kits", "/v1/drugs/$PACK"), server.asked)
    }

    /** Карточка и лист поверх неё спрашивают одну коробку разом — запрос один. */
    @Test
    fun twoAsksForOneThingAtOnceAreOneRequest() = runBlocking {
        server.hold()
        val card = launch(Dispatchers.Default) { freshening.pack(PACK) }
        val sheet = launch(Dispatchers.Default) { freshening.pack(PACK) }
        until("спросили сервер") { server.asked.isNotEmpty() }

        server.release()
        card.join()
        sheet.join()

        assertEquals(listOf("/v1/drugs/$PACK"), server.asked)
    }

    /** Не прочиталось — свежим не стало: следующее открытие спрашивает снова. */
    @Test
    fun aFailedReadIsNotFresh() = runBlocking {
        server.reachable = false
        freshening.pack(PACK)
        val failedAsks = server.asked.size

        server.reachable = true
        freshening.pack(PACK)

        assertEquals(failedAsks + 1, server.asked.size)
    }

    /**
     * Человек ушёл с экрана, пока чтение шло: перестаёт ждать он, а не чтение. Оно ложится, и
     * вернувшийся в окне сервер не спрашивает.
     */
    @Test
    fun leavingStopsTheWaitNotTheRead() = runBlocking {
        server.hold()
        val screen = launch(Dispatchers.Default) { freshening.pack(PACK) }
        until("спросили сервер") { server.asked.isNotEmpty() }

        screen.cancel()
        server.release()
        until("чтение легло") { server.laid.isNotEmpty() }
        freshening.pack(PACK)

        assertEquals(listOf("/v1/drugs/$PACK"), server.asked)
    }
}
