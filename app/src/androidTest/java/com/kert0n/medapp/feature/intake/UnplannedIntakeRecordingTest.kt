package com.kert0n.medapp.feature.intake

import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.fixture.factsOf
import java.time.ZoneOffset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.math.BigDecimal
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Разовый приём — факт и расход (PLAN D6, C1 «Разовый приём из занятого»). Своя полка списывает
 * здесь же, общая ставит `Consume` без брони; приём, который заденет занятое, не записывается,
 * пока человек не подтвердит, а после — мой курс зажат под оставшееся.
 */
@RunWith(AndroidJUnit4::class)
class UnplannedIntakeRecordingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var recording: UnplannedIntakeRecording
    private lateinit var adjusting: com.kert0n.medapp.feature.packages.PackageAdjusting

    private val sync = PackageSyncState(PACK, version = ResourceVersion(4), claimsVersion = ResourceVersion(2))

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        recording = Scenarios(database, LATER).unplannedIntakeRecording
        adjusting = Scenarios(database, LATER).packageAdjusting
    }

    @After
    fun tearDown() = database.close()

    private suspend fun local() {
        database.packageRepository().add(pack(quantity = tablets("20"), form = TABLET_FORM))
    }

    private suspend fun shared(claims: Claims? = null) {
        database.medKits().upsert(
            medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity()
        )
        database.packageRepository().add(
            pack(medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref, quantity = tablets("20"), form = TABLET_FORM),
            sync
        )
        // Картина броней — часть живой коробки со своей дверью: заведение пачки её не пишет.
        claims?.let { database.packageRepository().saveClaims(PACK, it) }
    }

    /** Курс держит коробку: пять доз по две таблетки — десять занято, десять свободно. */
    private suspend fun holdByACourse() {
        val plan = activeCourse(sources = listOf(source(PACK, 5)), totalDoses = 5)
        database.courseRepository().activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)))
    }

    private suspend fun commands(): List<SyncCommand> = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    private suspend fun allocated(): Doses? =
        database.courseRepository().findPlan(COURSE)?.sources?.firstOrNull { it.pkg.id == PACK }?.allocatedDoses

    @Test
    fun aLocalIntakeIsRecordedAndSpentAtOnce() = runTest {
        local()

        val outcome = recording.record(PACK, dose("3"), LATER) as UnplannedIntakeRecording.Outcome.Recorded

        assertEquals(IntakeAccounting.LOCAL_APPLIED, outcome.accounting)
        assertEquals(tablets("17"), database.packageRepository().find(PACK)?.quantity)
        val stored = requireNotNull(database.intakeRepository().find(outcome.intake.id))
        assertEquals(dose("3"), stored.taken?.amount)
        assertEquals(LATER, stored.taken?.at)
        assertEquals("Парацетамол", stored.taken?.pkg?.name)
        assertTrue(commands().isEmpty())
    }

    @Test
    fun aLocalIntakeThatEmptiesTheBoxEndsIt() = runTest {
        local()
        holdByACourse()

        recording.record(PACK, dose("20"), LATER, acknowledged = true)

        assertNull(database.packageRepository().find(PACK))
        assertEquals(emptyList<Uuid>(), database.courses().sourcePackagesOf(COURSE))
    }

    @Test
    fun moreThanTheLocalBoxHoldsIsRefusedWithoutWriting() = runTest {
        local()

        val outcome = recording.record(PACK, dose("25"), LATER, acknowledged = true)

        assertEquals(UnplannedIntakeRecording.Outcome.Rejected(IntakeRejected.Reason.INSUFFICIENT), outcome)
        assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
    }

    /** Свободно десять, принято двенадцать: без подтверждения ничего не записано, свободное названо. */
    @Test
    fun anIntakeIntoWhatTheCourseHoldsAsksFirst() = runTest {
        local()
        holdByACourse()

        val outcome = recording.record(PACK, dose("12"), LATER)

        assertEquals(UnplannedIntakeRecording.Outcome.Warned(listOf(IntakeWarning.TouchesReserved(tablets("10")))), outcome)
        assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
        assertEquals(Doses(5), allocated())
    }

    /** Подтверждено: записано, а курс зажат под оставшиеся восемь — четыре дозы. */
    @Test
    fun aConfirmedIntakeIntoWhatTheCourseHoldsClampsTheCourse() = runTest {
        local()
        holdByACourse()

        val outcome = recording.record(PACK, dose("12"), LATER, acknowledged = true)

        assertTrue(outcome is UnplannedIntakeRecording.Outcome.Recorded)
        assertEquals(tablets("8"), database.packageRepository().find(PACK)?.quantity)
        assertEquals(Doses(4), allocated())
    }

    /** На общей полке занятое — и чужие брони: 20 − 6 чужих − 10 моих = 4 свободно. */
    @Test
    fun aSharedIntakeCountsTheNeighboursClaimsAsReserved() = runTest {
        shared(claims = Claims(total = BigDecimal("16"), mine = BigDecimal("10")))
        holdByACourse()

        assertEquals(UnplannedIntakeRecording.Outcome.Warned(listOf(IntakeWarning.TouchesReserved(tablets("4")))), recording.record(PACK, dose("5"), LATER))
        assertTrue(commands().isEmpty())
    }

    /** Общая полка: факт записан, расход уезжает `Consume` без брони, число не трогается, курс зажат. */
    @Test
    fun aSharedIntakeIsAFactAndAConsumeWithoutAClaim() = runTest {
        shared()
        holdByACourse()

        val outcome = recording.record(PACK, dose("12"), LATER, acknowledged = true) as UnplannedIntakeRecording.Outcome.Recorded

        assertEquals(IntakeAccounting.PENDING, outcome.accounting)
        assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
        assertEquals(PackageStatus.ACTIVE, database.packageRepository().find(PACK)?.status)
        assertEquals(Doses(4), allocated())
        assertEquals(
            listOf(
                PackageSyncCommand.Consume(PACK, dose("12"), outcome.intake.id, claimAfter = null),
                PackageSyncCommand.SetClaim(PACK, tablets("8"))
            ),
            commands()
        )
    }

    @Test
    fun aBoxWaitingForItsRemovalTakesNoIntake() = runTest {
        local()
        database.packageRepository().mark(PACK, PackageStatus.REMOVING, by = Uuid.random())

        assertEquals(
            UnplannedIntakeRecording.Outcome.Rejected(IntakeRejected.Reason.PACKAGE_UNUSABLE),
            recording.record(PACK, dose("1"), LATER)
        )
    }

    /**
     * Человек открыл коробку, увидел там не двадцать, а пять, и пересчитал её. Связи нет,
     * подтверждённое число остаётся двадцать, а на экране — пять, и курс зажат под пять доз по
     * одной. Следом он пьёт одну таблетку мимо плана: на экране четыре — и курсу остаётся четыре
     * дозы, а не пять (PLAN D4, решение владельца 2026-09-13).
     *
     * Красная проверка: пока разовый приём считал от подтверждённого числа, он зажимал курс под
     * девятнадцать, лечение оставалось с пятью дозами, и нехватка вскрывалась только после ответа
     * сервера.
     */
    @Test
    fun anIntakeClampsByWhatThePersonSeesNotByTheConfirmedNumber() = runTest {
        shared()
        val plan = activeCourse(sources = listOf(source(PACK, 5)), totalDoses = 5, doseAmount = java.math.BigDecimal.ONE)
        database.courseRepository().activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)))
        adjusting.adjust(PACK, com.kert0n.medapp.feature.packages.PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("5")))
        assertEquals(Doses(5), allocated())

        recording.record(PACK, dose("1"), LATER, acknowledged = true)

        assertEquals(tablets("20"), requireNotNull(database.packageRepository().find(PACK)).quantity)
        assertEquals(tablets("4"), requireNotNull(database.packageRepository().projection(PACK)).availability.effective)
        assertEquals(Doses(4), allocated())
    }

    /**
     * Коробка лежит на общей полке, но сервер о ней ещё не знает: её создание только уехало.
     * Расход из неё местный — число меняется сразу, команды не ставятся, — а расскажет о нём то же
     * создание, собранное по прочитанной коробке (PLAN E6).
     *
     * Красная проверка: пока «отвечает ли коробка серверу» спрашивали только у полки, расход
     * уезжал командой; отказ создания — целевой полки не стало, пока команда ждала связи — закрывал
     * его каскадом, и выпитая таблетка пропадала из счёта.
     */
    @Test
    fun anIntakeFromABoxTheServerDoesNotKnowYetIsLocal() = runTest {
        database.medKits().upsert(
            medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity()
        )
        // Обвязки нет: первое подтверждённое число даст ответ на создание (PLAN E1).
        database.packageRepository().add(
            pack(medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref, quantity = tablets("20"), form = TABLET_FORM)
        )

        val outcome = recording.record(PACK, dose("1"), LATER)

        assertEquals(IntakeAccounting.LOCAL_APPLIED, (outcome as UnplannedIntakeRecording.Outcome.Recorded).accounting)
        assertEquals(tablets("19"), requireNotNull(database.packageRepository().find(PACK)).quantity)
        assertTrue(commands().none { it is PackageSyncCommand.Consume })
    }

    /**
     * Просрочено и заденет занятое — оба вопроса сразу, и одно подтверждение пишет приём
     * (PLAN D6). Годен до сегодня — не вопрос.
     */
    @Test
    fun expiryAndReservedAreAskedTogetherAndAnsweredOnce() = runTest {
        local()
        holdByACourse()
        val today = LATER.atZone(ZoneOffset.UTC).toLocalDate()
        database.packageRepository().describe(PACK, factsOf(pack(quantity = tablets("20"), form = TABLET_FORM)).copy(expiresOn = ExpiryDate(today.minusDays(1))))

        val asked = recording.record(PACK, dose("12"), LATER)

        assertEquals(
            UnplannedIntakeRecording.Outcome.Warned(listOf(IntakeWarning.Expired(ExpiryDate(today.minusDays(1))), IntakeWarning.TouchesReserved(tablets("10")))),
            asked
        )
        assertEquals(tablets("20"), requireNotNull(database.packageRepository().find(PACK)).quantity)

        assertTrue(recording.record(PACK, dose("12"), LATER, acknowledged = true) is UnplannedIntakeRecording.Outcome.Recorded)
        assertEquals(tablets("8"), requireNotNull(database.packageRepository().find(PACK)).quantity)

        // Годен до сегодня — не просрочен: остаётся только вопрос о занятом (курс зажат под остаток).
        database.packageRepository().describe(PACK, factsOf(pack(quantity = tablets("8"), form = TABLET_FORM)).copy(expiresOn = ExpiryDate(today)))
        val onlyReserved = recording.record(PACK, dose("1"), LATER) as UnplannedIntakeRecording.Outcome.Warned
        assertEquals(listOf(IntakeWarning.TouchesReserved(tablets("0"))), onlyReserved.warnings)
    }
}
