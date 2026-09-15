package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.queue.Settlement.Effect
import com.kert0n.medapp.queue.Settlement.Transition
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Исход доставки переводится в переход и эффекты там, где живёт доставка, — чистой функцией без
 * базы. Хранение получает список и применяет его, не толкуя (PLAN E3, F5).
 */
class SettlementTest {

    private val consume = PackageSyncCommand.Consume(PACK, dose("3"), INTAKE)
    private val leave = MedKitSyncCommand.Leave(SHARED_KIT)
    private val snapshot = PackageSnapshot(
        pack(quantity = tablets("17"), medKit = medKit(id = HOME_KIT, publication = MedKit.Publication.PUBLISHED).ref),
        PackageSyncState(PACK, ResourceVersion(4), ResourceVersion(2))
    )
    private val later: Instant = Instant.parse("2026-09-12T12:00:00Z")

    @Test
    fun appliedWithASnapshotClosesLaysItDownAndAccountsTheIntake() {
        val settlement = Delivery.Applied(PackageState.Present(snapshot)).settlement(consume)
        assertEquals(Transition.Close.Applied, settlement.transition)
        assertEquals(
            listOf(Effect.Account(IntakeAccounting.REMOTE_APPLIED), Effect.LayDown(snapshot), Effect.Settled),
            settlement.effects
        )
    }

    @Test
    fun appliedWithThePackageGoneEndsIt() {
        val settlement = Delivery.Applied(PackageState.Gone).settlement(consume)
        assertEquals(listOf(Effect.Account(IntakeAccounting.REMOTE_APPLIED), Effect.PackageEnded(PACK), Effect.Settled), settlement.effects)
    }

    @Test
    fun appliedLeaveTakesTheShelfAndNotAPackage() {
        // Выход касается полки целиком, а не какой-то её коробки: состояния пачки у него нет
        // вовсе, а следствие — то, что до согласия сервера не трогали (PLAN E6).
        val settlement = Delivery.Applied(PackageState.None).settlement(leave)
        assertEquals(Transition.Close.Applied, settlement.transition)
        assertEquals(
            listOf(Effect.Account(IntakeAccounting.REMOTE_APPLIED), Effect.MedKitLeft(SHARED_KIT), Effect.Settled),
            settlement.effects
        )
    }

    /** Разбор полки приходит тем же путём и несёт, куда девать её содержимое (PLAN E6). */
    @Test
    fun appliedDismantleCarriesWhereTheContentsGo() {
        val settlement = Delivery.Applied(PackageState.None)
            .settlement(MedKitSyncCommand.Delete(SHARED_KIT, transferTo = HOME_KIT))
        assertEquals(
            listOf<Effect>(
                Effect.Account(IntakeAccounting.REMOTE_APPLIED),
                Effect.MedKitDismantled(SHARED_KIT, transferTo = HOME_KIT),
                Effect.Settled
            ),
            settlement.effects
        )
    }

    /** «Пачки нет» после нашего же `Delete` — конец коробки; чем вызван, база не различает (D7). */
    @Test
    fun theBoxGoneAfterOurDeleteEnds() {
        assertEquals(
            listOf<Effect>(
                Effect.Account(IntakeAccounting.REMOTE_APPLIED),
                Effect.PackageEnded(PACK),
                Effect.Settled
            ),
            Delivery.Applied(PackageState.Gone).settlement(PackageSyncCommand.Delete(PACK)).effects
        )
    }

    /**
     * Унесённую домой коробку сервер забыл — это не конец коробки: она у человека (PLAN E6). Отказ
     * возвращает её на прежнюю полку раньше, чем ляжет ответ.
     */
    @Test
    fun aWithdrawnBoxStaysLocalOrReturnsToItsShelf() {
        val withdraw = PackageSyncCommand.Withdraw(PACK, SHARED_KIT, tablets("20"))
        assertEquals(
            listOf(Effect.Account(IntakeAccounting.REMOTE_APPLIED), Effect.Withdrawn(PACK), Effect.Settled),
            Delivery.Applied(PackageState.Gone).settlement(withdraw).effects
        )
        assertEquals(
            listOf(
                Effect.Account(IntakeAccounting.REMOTE_REFUSED),
                Effect.Withdrawn(PACK),
                Effect.Cascade(Transition.Close.AccessLost, IntakeAccounting.REMOTE_REFUSED),
                Effect.Settled
            ),
            Delivery.AccessLost.settlement(withdraw).effects
        )
        assertEquals(
            listOf(
                Effect.Account(IntakeAccounting.REMOTE_REFUSED),
                Effect.Returned(PACK, SHARED_KIT),
                Effect.LayDown(snapshot),
                Effect.Cascade(Transition.Close.Refused(RefusalReason.SUPERSEDED), IntakeAccounting.REMOTE_REFUSED),
                Effect.Settled
            ),
            Delivery.Refused(RefusalReason.STALE, PackageState.Present(snapshot)).settlement(withdraw).effects
        )
    }

    /**
     * Расход применён, а коробка уже на полке, где нас нет: учёт — применён, коробка у нас кончается
     * утратой доступа (PLAN E3, E6).
     */
    @Test
    fun appliedButElsewhereKeepsTheAccountingAndLosesTheBox() {
        assertEquals(
            listOf(
                Effect.Account(IntakeAccounting.REMOTE_APPLIED),
                Effect.PackageEnded(PACK),
                Effect.Settled
            ),
            Delivery.Applied(PackageState.Elsewhere).settlement(consume).effects
        )
    }

    @Test
    fun staleRepreparesUnderTheSameNumberAndLaysTheSnapshotDown() {
        val settlement = Delivery.Stale(snapshot, notBefore = later).settlement(consume)
        assertEquals(Transition.Reprepare("устарело: ${ResourceVersion(4)}", later), settlement.transition)
        assertEquals(listOf<Effect>(Effect.LayDown(snapshot)), settlement.effects)
    }

    @Test
    fun refusedClosesWithTheReasonAccountsAndCascades() {
        val settlement = Delivery.Refused(RefusalReason.INSUFFICIENT, PackageState.Present(snapshot)).settlement(consume)
        assertEquals(Transition.Close.Refused(RefusalReason.INSUFFICIENT), settlement.transition)
        assertEquals(
            listOf(
                Effect.Account(IntakeAccounting.REMOTE_REFUSED),
                Effect.LayDown(snapshot),
                Effect.Cascade(Transition.Close.Refused(RefusalReason.SUPERSEDED), IntakeAccounting.REMOTE_REFUSED),
                Effect.Settled
            ),
            settlement.effects
        )
    }

    @Test
    fun retryOnlyMovesTheOperationBackToWaiting() {
        val settlement = Delivery.Retry("ответ потерян", later, attempted = true, outcomeUnknown = true).settlement(consume)
        assertEquals(Transition.Retry("ответ потерян", attempted = true, outcomeUnknown = true, notBefore = later), settlement.transition)
        assertEquals(emptyList<Effect>(), settlement.effects)
    }

    @Test
    fun accessLostMarksThePackageAccountsAndCascades() {
        val settlement = Delivery.AccessLost.settlement(consume)
        assertEquals(Transition.Close.AccessLost, settlement.transition)
        assertEquals(
            listOf(
                Effect.Account(IntakeAccounting.REMOTE_REFUSED),
                Effect.PackageEnded(PACK),
                Effect.Cascade(Transition.Close.AccessLost, IntakeAccounting.REMOTE_REFUSED),
                Effect.Settled
            ),
            settlement.effects
        )
    }

    @Test
    fun accessLostOfAMedKitCommandHasNoPackageToMark() {
        val settlement = Delivery.AccessLost.settlement(leave)
        assertEquals(
            listOf(
                Effect.Account(IntakeAccounting.REMOTE_REFUSED),
                Effect.Cascade(Transition.Close.AccessLost, IntakeAccounting.REMOTE_REFUSED),
                Effect.Settled
            ),
            settlement.effects
        )
    }

    /**
     * Пометку снимает только закрытие: отказ возвращает вещь в оборот, применение отпускает её, а
     * «устарело» и повтор — ещё не ответ, и решение продолжает ждать (PLAN E1).
     */
    @Test
    fun onlyAClosedCommandReleasesTheMark() {
        val delete = PackageSyncCommand.Delete(PACK)
        for (closing in listOf(Delivery.Applied(PackageState.None), Delivery.Refused(RefusalReason.STALE, PackageState.None), Delivery.AccessLost)) {
            assertEquals(Effect.Settled, closing.settlement(delete).effects.last())
        }
        for (waiting in listOf(Delivery.Stale(snapshot), Delivery.Retry("обрыв"))) {
            assertEquals(false, Effect.Settled in waiting.settlement(delete).effects)
        }
    }

    /** Закрытие — три случая, и у каждого свой закрытый статус; причина есть ровно у отказа. */
    @Test
    fun aCloseNamesItsClosedStatusAndOnlyARefusalCarriesAReason() {
        assertEquals(SyncOperationStatus.APPLIED, Transition.Close.Applied.status)
        assertEquals(SyncOperationStatus.ACCESS_LOST, Transition.Close.AccessLost.status)
        assertEquals(SyncOperationStatus.REFUSED, Transition.Close.Refused(RefusalReason.CONFLICT).status)
        assertEquals(RefusalReason.CONFLICT, Transition.Close.Refused(RefusalReason.CONFLICT).refusalReason)
        assertEquals(null, Transition.Close.Applied.refusalReason)
        assertEquals(null, Transition.Close.AccessLost.refusalReason)
    }
}
