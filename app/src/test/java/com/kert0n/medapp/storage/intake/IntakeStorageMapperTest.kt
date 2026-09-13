package com.kert0n.medapp.storage.intake

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeAnswer
import com.kert0n.medapp.domain.intake.TakenDose
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.intake.UnplannedIntake
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.unplannedIntake
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.intake.IntakeSyncState
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.toStorageRow

/**
 * Плановый пункт и внеплановый факт лежат в одной таблице и различаются наличием курса.
 * Учёт расхода едет в тех же колонках, но доменная модель его не носит (PLAN D6, F1).
 */
class IntakeStorageMapperTest {

    @Test
    fun plannedIntakeComesBackPlanned() {
        val planned = plannedIntake()
        val restored = planned.toStorageRow().toDomain(VOCABULARY) as CourseIntake

        assertEquals(planned.id, restored.id)
        assertEquals(planned.courseId, restored.courseId)
        assertEquals(planned.courseRevision, restored.courseRevision)
        assertEquals(planned.slot, restored.slot)
        assertEquals(planned.plannedAmount, restored.plannedAmount)
        assertEquals(planned.plannedPackage, restored.plannedPackage)
        assertEquals(IntakeStatus.PLANNED, restored.status)
        assertNull(restored.answer)
    }

    @Test
    fun unsuppliedIntakeStaysUnsupplied() {
        val restored = plannedIntake(plannedPackage = null).toStorageRow().toDomain(VOCABULARY) as CourseIntake
        assertNull(restored.plannedPackage)
        assertEquals(false, restored.isSupplied)
    }

    /** Пачка факта может отличаться от плановой, и аптечка — у пачки. */
    @Test
    fun confirmedIntakeKeepsWhereTheDoseCameFrom() {
        val taken = plannedIntake().confirm(pack(id = OTHER_PACK, medKit = medKit(id = SHARED_KIT, name = "Дача").ref).take(dose("1.5"), LATER).getOrThrow())
        val restored = taken.toStorageRow().toDomain(VOCABULARY) as CourseIntake

        assertEquals(IntakeStatus.TAKEN, restored.status)
        assertEquals(taken.taken, restored.taken)
        assertEquals(OTHER_PACK, restored.taken?.pkg?.id)
        assertEquals(PACK, restored.plannedPackage?.id)
    }

    /**
     * Пачку удалили, а приём был: строка приёма отдаёт количество и момент, а ссылка на пачку
     * пуста — её обнулила сама схема (`SET NULL`, PLAN D6, F1). История лечения держится на
     * записи эпизода и на самом приёме.
     *
     * Красная проверка: потребовать пачку при чтении — прошлое станет нечитаемым ровно тогда,
     * когда человек выбросил аптечку.
     */
    /** Сосед сменил единицу пачки на сервере: приёмы в таблетках читаются по-прежнему. */
    @Test
    fun historyIsReadableAfterThePackChangedItsUnit() {
        val confirmed = plannedIntake().confirm(pack(quantity = tablets("10")).take(dose("2"), LATER).getOrThrow())
        val row = IntakeStorageRow(
            intake = confirmed.toStorageEntity(),
            planned = confirmed.plannedPackage?.toStorageRow(),
            taken = pack(quantity = millilitres("100")).ref.toStorageRow()
        )

        val restored = row.toDomain(VOCABULARY) as CourseIntake

        assertEquals(dose("2"), restored.taken?.amount)
        assertEquals(MILLILITRES, restored.taken?.pkg?.unit)
    }

    @Test
    fun everyAnswerComesBackAsItself() {
        val missed = plannedIntake().miss(LATER)
        val cancelled = plannedIntake().cancel(LATER)

        for (answered in listOf(missed, cancelled)) {
            val restored = answered.toStorageRow().toDomain(VOCABULARY) as CourseIntake
            assertEquals(answered.status, restored.status)
            assertEquals(answered.answer, restored.answer)
            assertNull(restored.taken)
        }
    }

    @Test
    fun unplannedFactHasNoCourseAndIsAlwaysTaken() {
        val unplanned = unplannedIntake(takenAmount = dose("1"))
        val stored = unplanned.toStorageEntity()
        assertNull(stored.courseId)
        assertNull(stored.plannedAmount)

        val restored = unplanned.toStorageRow().toDomain(VOCABULARY)
        assertTrue(restored is UnplannedIntake)
        assertEquals(IntakeStatus.TAKEN, restored.status)
        assertEquals(unplanned.dose, (restored as UnplannedIntake).dose)
    }

    /** Учёт расхода — обвязка доставки: он едет в колонках и не приезжает обратно в домен. */
    @Test
    fun accountingTravelsBesideTheIntakeAndNotInsideIt() {
        val operation = Uuid.parse("00000000-0000-4000-8000-000000000071")
        val sync = IntakeSyncState(
            intakeId = INTAKE,
            accounting = IntakeAccounting.PENDING,
            operationId = operation
        )
        val confirmed = plannedIntake().confirm(pack().take(dose("2"), LATER).getOrThrow())
        val stored = confirmed.toStorageEntity(sync)

        assertEquals(sync, stored.syncState())
        assertEquals(IntakeStatus.TAKEN, confirmed.toStorageRow(sync).toDomain(VOCABULARY).status)
    }

    @Test
    fun accountingOfAnotherIntakeIsRejected() {
        val alien = IntakeSyncState(intakeId = COURSE)
        val failure = runCatching { plannedIntake().toStorageEntity(alien) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class, failure!!::class)
    }
}
