package com.kert0n.medapp.storage.intake

import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.intake.IntakeSyncState
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Что записывается вместе с ответом на приём. Проверяются связи между частями: порознь их не
 * бывает, и хранение это отвергает, а не пишет половину (PLAN D6, F5).
 */
class IntakeOutcomeTest {

    private val operation: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000091")

    private fun confirmed() = plannedIntake().confirm(pack().take(dose("2"), LATER).getOrThrow())

    private fun outcome(sync: IntakeSyncState) = IntakeOutcome(
        intake = confirmed(),
        expected = setOf(IntakeStatus.PLANNED),
        sync = sync,
        recordedAt = LATER
    )

    /** Уехавший командой расход локальный остаток не трогает; локальный — трогает. */
    @Test
    fun onlyALocallyAppliedSpendTouchesTheLocalAmount() {
        assertEquals(false, outcome(IntakeSyncState(INTAKE, IntakeAccounting.PENDING, operationId = operation)).spendsLocally)
        assertEquals(true, outcome(IntakeSyncState(INTAKE, IntakeAccounting.LOCAL_APPLIED)).spendsLocally)
    }

    /** Условный переход называет, откуда идёт, и не идёт в тот же статус. */
    @Test
    fun theTransitionNamesItsOriginAndIsNotATransitionToItself() {
        assertThrows(IllegalArgumentException::class.java) {
            IntakeOutcome(confirmed(), expected = emptySet(), sync = IntakeSyncState(INTAKE, IntakeAccounting.LOCAL_APPLIED), recordedAt = LATER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IntakeOutcome(confirmed(), expected = setOf(IntakeStatus.TAKEN), sync = IntakeSyncState(INTAKE, IntakeAccounting.LOCAL_APPLIED), recordedAt = LATER)
        }
    }

    /** Момент ответа — из домена, а не из часов хранения. */
    @Test
    fun answeredAtComesFromTheAnswer() {
        assertEquals(LATER, outcome(IntakeSyncState(INTAKE, IntakeAccounting.LOCAL_APPLIED)).answeredAt)
    }
}
