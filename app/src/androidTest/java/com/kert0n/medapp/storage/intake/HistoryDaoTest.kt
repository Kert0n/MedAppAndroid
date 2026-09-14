package com.kert0n.medapp.storage.intake

import android.database.sqlite.SQLiteConstraintException
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.FIRST_PLANNED_AT
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.OTHER_INTAKE
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.rejectedByDatabase
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.storage.course.toStorageEntity as toRecordStorageEntity
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.fixture.save
import com.kert0n.medapp.storage.pack.ClaimsStorageEntity
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.TABLETS_ID
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.FIRST_SCHEDULED_ON

/**
 * Что переживает конец коробки, а что уходит вместе с ней (PLAN D3, D6, F2).
 *
 * Приёмы держатся за запись о коробке, а не за живую строку: коробки нет — история читается
 * по-прежнему, с именем и единицей из записи. Части живой коробки — сведения, брони,
 * связи с курсами — уходят вместе с ней.
 */
class HistoryDaoTest {

    private lateinit var database: MedAppDatabase
    private val intakes get() = database.intakes()

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        for (id in listOf(PACK, OTHER_PACK)) {
            val pkg = pack(id = id)
            database.packages().save(pkg)
        }
        database.courses().upsertRecord(courseRecord().toRecordStorageEntity())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun plannedIntakeAndItsConfirmationComeBackWhole() = runTest {
        intakes.upsert(plannedIntake().toStorageEntity())
        val stored = requireNotNull(intakes.find(INTAKE)).toDomain(VOCABULARY)
        assertEquals(IntakeStatus.PLANNED, stored.status)

        val taken = plannedIntake().confirm(pack().take(dose("2"), LATER).getOrThrow())
        intakes.upsert(taken.toStorageEntity())
        assertEquals(taken.taken, requireNotNull(intakes.find(INTAKE)).toDomain(VOCABULARY).taken)
    }

    /** Один пункт расписания заводится один раз: повторная материализация идемпотентна. */
    @Test
    fun sameSlotIsNotMaterialisedTwice() = runTest {
        val slot = plannedIntake().toStorageEntity()
        intakes.insertPlannedIfMissing(listOf(slot))
        val again = plannedIntake(id = OTHER_INTAKE).toStorageEntity()

        val inserted = intakes.insertPlannedIfMissing(listOf(again))

        assertEquals(listOf(-1L), inserted)
        assertNotNull(intakes.find(INTAKE))
        assertEquals(null, intakes.find(OTHER_INTAKE))
    }

    /**
     * Поздний ответ вернул конец лечения назад — последний материализованный пункт стал лишним.
     * Убирается только плановое: факт остаётся, даже если его пункта нет среди оставшихся.
     */
    @Test
    fun pruningRemovesOnlyThePlannedSlotsOutsideTheRemainingOnes() = runTest {
        val repository = database.intakeRepository()
        val taken = plannedIntake().confirm(pack().take(dose("2"), LATER).getOrThrow())
        val extra = plannedIntake(id = OTHER_INTAKE, scheduledOn = FIRST_SCHEDULED_ON.plusDays(7))
        intakes.upsert(taken.toStorageEntity())
        intakes.upsert(extra.toStorageEntity())

        val pruned = repository.prunePlanned(COURSE, keep = emptySet())

        assertEquals(listOf(OTHER_INTAKE), pruned)
        assertNotNull(intakes.find(INTAKE))
        assertEquals(null, intakes.find(OTHER_INTAKE))
    }

    /** Ответ идёт условным `UPDATE`: повтор уже совершённого ничего не меняет второй раз. */
    @Test
    fun answeringTwiceChangesNothingTheSecondTime() = runTest {
        intakes.upsert(plannedIntake().toStorageEntity())

        val first = intakes.answerIfStatusIs(
            id = INTAKE,
            from = listOf(IntakeStatus.PLANNED, IntakeStatus.MISSED),
            to = IntakeStatus.TAKEN,
            at = LATER,
            packageId = PACK,
            amount = "2",
            unitId = TABLETS_ID,
            accounting = IntakeAccounting.LOCAL_APPLIED,
            operationId = null
        )
        val second = intakes.answerIfStatusIs(
            id = INTAKE,
            from = listOf(IntakeStatus.PLANNED),
            to = IntakeStatus.MISSED,
            at = LATER.plusSeconds(60),
            packageId = null,
            amount = null,
            unitId = TABLETS_ID,
            accounting = IntakeAccounting.NOT_APPLICABLE,
            operationId = null
        )

        assertEquals(1, first)
        assertEquals(0, second)
        val stored = requireNotNull(intakes.findEntity(INTAKE))
        assertEquals(IntakeStatus.TAKEN, stored.status)
        assertEquals(IntakeAccounting.LOCAL_APPLIED, stored.accounting)
    }

    /**
     * Приём переживает коробку: количество и момент записаны в нём самом, а имя и единица
     * читаются из записи о коробке, которая остаётся (PLAN D3, D6).
     *
     * Красная проверка: посадить ключ приёма на живую строку — человек не сможет выбросить
     * коробку, из которой хоть раз принимал, либо история потеряет, из чего принимали.
     */
    @Test
    fun anIntakeOutlivesThePackageItCameFrom() = runTest {
        intakes.upsert(plannedIntake().confirm(pack().take(dose("2"), LATER).getOrThrow()).toStorageEntity())

        assertEquals(1, database.packages().delete(PACK))

        val left = requireNotNull(intakes.find(INTAKE)).toDomain(VOCABULARY)
        assertEquals(IntakeStatus.TAKEN, left.status)
        assertEquals(dose("2"), left.taken?.amount)
        assertEquals(LATER, left.taken?.at)
        assertEquals("Парацетамол", left.taken?.pkg?.name)
        assertEquals(TABLETS, left.taken?.pkg?.unit)
    }

    /** Части живой коробки уходят с ней: сведения и брони без коробки не значат ничего (PLAN F1, F2). */
    @Test
    fun thePartsOfAPackageGoAwayWithIt() = runTest {
        database.packages().save(pack(note = "в машине"))
        database.packages().upsertClaims(ClaimsStorageEntity(PACK, "5", null))

        assertEquals(1, database.packages().delete(PACK))

        assertNull(database.packages().find(PACK))
        assertEquals(0, count("package_details", PACK))
        assertEquals(0, count("claims", PACK))
        assertNotNull(database.packages().find(OTHER_PACK))
        assertEquals(1, count("package_details", OTHER_PACK))
    }

    /** Запись о коробке остаётся: за неё держится история, и ключом она не удаляется (D3). */
    @Test
    fun theRecordOfAPackageStays() = runTest {
        intakes.upsert(plannedIntake().confirm(pack().take(dose("2"), LATER).getOrThrow()).toStorageEntity())
        assertEquals(1, database.packages().delete(PACK))

        assertEquals(1, count("package_records", PACK))
        val refusal = rejectedByDatabase {
            database.openHelper.writableDatabase
                .execSQL("DELETE FROM package_records WHERE id = '$PACK'")
        }
        assertTrue("$refusal", refusal is SQLiteConstraintException)
    }

    /**
     * Запись эпизода тоже под `RESTRICT`. Удаления записей у приложения нет вовсе, поэтому
     * попытка идёт прямым SQL: ограничение и стоит ради ошибки, которой в коде ещё нет.
     */
    @Test
    fun recordWithHistoryCannotBeDeleted() = runTest {
        intakes.upsert(plannedIntake().toStorageEntity())
        val refusal = rejectedByDatabase {
            database.openHelper.writableDatabase
                .execSQL("DELETE FROM course_records WHERE id = '$COURSE'")
        }
        assertTrue("$refusal", refusal is SQLiteConstraintException)
    }

    private fun count(table: String, packageId: Uuid): Int {
        val column = if (table == "package_records") "id" else "package_id"
        return database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table WHERE $column = '$packageId'")
            .use { it.moveToFirst(); it.getInt(0) }
    }
}
