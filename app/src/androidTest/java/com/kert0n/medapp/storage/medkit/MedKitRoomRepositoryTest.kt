package com.kert0n.medapp.storage.medkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Хранение аптечки: пометка ставится её же переходом, обвязка синхронизации живёт отдельно от
 * доменных полей, а снимок сервера трогает только число участников (PLAN E4, F1).
 */
class MedKitRoomRepositoryTest {

    private lateinit var database: MedAppDatabase
    private lateinit var medKits: MedKitRoomRepository

    private val at: Instant = Instant.parse("2026-09-10T12:00:00Z")

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        medKits = database.medKitRepository()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    /**
     * Пометку ставит переход самой аптечки, прочитанной здесь же: решение о полке, о которой уже
     * принято другое, не записывается (PLAN E1, E5).
     */
    @Test
    fun aMarkComesFromTheKitsOwnTransition() = runTest {
        assertEquals(true, medKits.mark(HOME_KIT, MedKitStatus.PUBLISHING))
        assertEquals(MedKitStatus.PUBLISHING, requireNotNull(medKits.find(HOME_KIT)).status)
        assertEquals(MedKit.Publication.LOCAL, requireNotNull(medKits.find(HOME_KIT)).publication)

        assertNotNull(runCatching { medKits.mark(HOME_KIT, MedKitStatus.REMOVING) }.exceptionOrNull())
    }

    /** Снимать пометку — дело ответа сервера, и решением это не делается. */
    @Test
    fun aMarkIsNotLiftedByADecision() = runTest {
        assertNotNull(runCatching { medKits.mark(HOME_KIT, MedKitStatus.ACTIVE) }.exceptionOrNull())
    }
}

/**
 * Полка приходит вместе с содержимым: сколько живых коробок и сколько просрочено на названный
 * день, одним запросом на весь список (PLAN D2, REQ-055).
 */
@RunWith(AndroidJUnit4::class)
class MedKitContentsReadingTest {

    private lateinit var database: MedAppDatabase
    private val queries = mutableListOf<String>()

    private val today: LocalDate = LocalDate.of(2026, 9, 10)

    @Before
    fun openDatabase() {
        database = inMemoryDatabase { sql -> synchronized(queries) { queries += sql } }
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private suspend fun contentsOf(medKitId: Uuid): MedKitContents =
        requireNotNull(database.medKitRepository().observeAll(today).first().single { it.id == medKitId }).contents

    /** «Годен до» включительно: сегодняшний день ещё годен, вчерашний — уже просрочен (PLAN D3). */
    @Test
    fun expiryBoundaryIsTheDayNotTheClock() = runTest {
        val packages = database.packageRepository()
        packages.add(pack(id = Uuid.random(), name = "Сегодня", expiresOn = ExpiryDate(today)))
        packages.add(pack(id = Uuid.random(), name = "Вчера", expiresOn = ExpiryDate(today.minusDays(1))))
        packages.add(pack(id = Uuid.random(), name = "Без срока"))

        assertEquals(MedKitContents(packages = 3, expired = 1), contentsOf(HOME_KIT))
        assertEquals(MedKitContents.EMPTY, contentsOf(SHARED_KIT))
        assertEquals(
            MedKitContents(packages = 3, expired = 1),
            requireNotNull(database.medKitRepository().observe(HOME_KIT, today).first()).contents
        )
    }

    /** Кончившаяся коробка на полке не лежит и не считается — ни как коробка, ни как просрочка. */
    @Test
    fun aFinishedPackageIsNotCounted() = runTest {
        val scenarios = Scenarios(database, LATER)
        database.packageRepository().add(pack(id = PACK, expiresOn = ExpiryDate(today.minusDays(1))))
        database.packageRepository().add(pack(id = Uuid.random(), name = "Другая"))
        assertEquals(MedKitContents(packages = 2, expired = 1), contentsOf(HOME_KIT))

        scenarios.packageAdjusting.adjust(PACK, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("0")))

        assertEquals(MedKitContents(packages = 1, expired = 0), contentsOf(HOME_KIT))
    }

    /**
     * Сто полок — один запрос по коробкам и один по полкам, а не по запросу на полку
     * (красная проверка: считать содержимое по полке — сто чтений `packages`).
     */
    @Test
    fun aHundredShelvesAreReadWithOneQueryEach() = runTest {
        val packages = database.packageRepository()
        repeat(100) { index ->
            val kit = medKit(id = Uuid.random(), name = "Полка $index")
            database.medKits().upsert(kit.toMedKitStorageEntity())
            packages.add(pack(id = Uuid.random(), medKit = kit.ref, name = "Пачка $index"))
            packages.add(pack(id = Uuid.random(), medKit = kit.ref, name = "Просроченная $index", expiresOn = ExpiryDate(today.minusDays(1))))
        }
        synchronized(queries) { queries.clear() }

        val shelves = database.medKitRepository().observeAll(today).first()

        assertEquals(102, shelves.size)
        assertEquals(100, shelves.count { it.contents == MedKitContents(packages = 2, expired = 1) })
        val reads = synchronized(queries) { queries.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) } }
        assertEquals("чтений packages: $reads", 1, reads.count { it.contains("FROM packages") })
        assertEquals("чтений med_kits: $reads", 1, reads.count { it.contains("FROM med_kits") })
    }
}
