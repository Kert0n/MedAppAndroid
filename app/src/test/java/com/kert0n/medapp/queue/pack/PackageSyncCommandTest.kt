package com.kert0n.medapp.queue.pack

import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.OTHER_INTAKE
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.tablets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Команда очереди по упаковке — величина, а вид команды — тип.
 *
 * Проверяется не «оно компилируется», а то, что неверное состояние **невыразимо**: у расхода нет
 * способа не назвать приём, у описания нет способа потерять исходное состояние, а нулевая бронь
 * не притворяется снятием.
 */
class PackageSyncCommandTest {

    private val paracetamol = PackageSharedFacts(name = "Парацетамол", form = TABLET_FORM)

    @Test
    fun anotherAmountIsAnotherCommand() {
        // Величина: подготовленный запрос неизменен, «поправить команду на месте» не бывает.
        assertNotEquals(
            PackageSyncCommand.CorrectStock(PACK, tablets("20"), tablets("20")),
            PackageSyncCommand.CorrectStock(PACK, tablets("20"), tablets("19"))
        )
        assertEquals(
            PackageSyncCommand.CorrectStock(PACK, tablets("20"), tablets("20")),
            PackageSyncCommand.CorrectStock(PACK, tablets("20"), tablets("20.000000"))
        )
    }

    @Test
    fun everyCommandNamesItsPackage() {
        // Поле корня, а не разбор вариантов: строка очереди называет пачку своей колонкой.
        val commands: List<PackageSyncCommand> = listOf(
            PackageSyncCommand.Create(PACK, HOME_KIT, tablets("20"), paracetamol),
            PackageSyncCommand.Describe(PACK, paracetamol, paracetamol.copy(country = "Украина")),
            PackageSyncCommand.CorrectStock(PACK, tablets("20"), tablets("19")),
            PackageSyncCommand.Move(PACK, SHARED_KIT),
            PackageSyncCommand.Delete(PACK),
            PackageSyncCommand.Withdraw(PACK, SHARED_KIT, tablets("20")),
            PackageSyncCommand.Consume(PACK, dose("2"), INTAKE),
            PackageSyncCommand.SetClaim(PACK, tablets("10")),
            PackageSyncCommand.ReleaseClaim(PACK)
        )
        assertEquals(9, commands.size)
        assertEquals(listOf(PACK), commands.map { it.packageId }.distinct())
    }

    /**
     * «Унёс домой» на проводе — то же удаление, но остатка не меняет: коробка у человека, и чего
     * нет на сервере, то уже унесено (PLAN E1, E6).
     */
    @Test
    fun carryingHomeKeepsTheAmountAndTakesAnAbsentBoxAsDone() {
        val withdraw = PackageSyncCommand.Withdraw(PACK, SHARED_KIT, tablets("20"))
        assertEquals(null, withdraw.appliedTo(tablets("20")))
        assertEquals(com.kert0n.medapp.queue.NotFoundPolicy.APPLIED, withdraw.onNotFound)
    }

    /**
     * Пересчёт — разница относительно увиденного, а не названное число (C1): видел 20, назвал 17 —
     * это «минус три» поверх любого свежего числа; поверх 10 выходит 7, а поверх 2 — ниже нуля, и
     * такое соотнести нельзя.
     */
    @Test
    fun recountIsADifferenceOverWhatWasSeen() {
        val recount = PackageSyncCommand.CorrectStock(PACK, seen = tablets("20"), actual = tablets("17"))
        assertEquals(tablets("7"), recount.onto(tablets("10")))
        assertEquals(tablets("0"), recount.onto(tablets("2")))
        assertTrue(recount.conflictsWith(tablets("2")))
        assertFalse(recount.conflictsWith(tablets("3")))
        assertEquals(TABLETS, recount.measuredIn)
    }

    /**
     * Правка сведений ложится своими полями поверх серверных: нетронутое остаётся соседским,
     * изменённое ложится, если сосед его не трогал или сделал то же, а то же поле, изменённое
     * соседом иначе, соотнести нельзя (C1).
     */
    @Test
    fun describingLaysOnlyItsOwnFieldsOverTheServers() {
        val describe = PackageSyncCommand.Describe(PACK, paracetamol, paracetamol.copy(country = "Украина"))
        val neighbourRenamed = paracetamol.copy(name = "Панадол")
        assertEquals(neighbourRenamed.copy(country = "Украина"), describe.onto(neighbourRenamed))
        assertEquals(paracetamol.copy(country = "Украина"), describe.onto(paracetamol.copy(country = "Украина")))
        assertEquals(null, describe.onto(paracetamol.copy(country = "Польша")))
    }

    @Test
    fun consumeAlwaysNamesItsIntake() {
        // Команда приёма не поглощает следующий факт: у каждого подтверждения свой id.
        assertNotEquals(
            PackageSyncCommand.Consume(PACK, dose("2"), INTAKE),
            PackageSyncCommand.Consume(PACK, dose("2"), OTHER_INTAKE)
        )
    }

    @Test
    fun claimAfterIsMeasuredByTheSameUnitAsTheConsumption() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageSyncCommand.Consume(PACK, dose("2"), INTAKE, claimAfter = millilitres("10"))
        }
    }

    @Test
    fun threeMeaningsOfClaimAfterAreDistinguishable() {
        // null — внеплановый расход; положительное — новая абсолютная бронь; ноль — курсовой
        // расход без блока брони, снятие уезжает зависимой командой (PLAN E2).
        assertEquals(null, PackageSyncCommand.Consume(PACK, dose("2"), INTAKE).claimAfter)
        assertEquals(
            tablets("8"),
            PackageSyncCommand.Consume(PACK, dose("2"), INTAKE, tablets("8")).claimAfter
        )
        assertEquals(
            tablets("0"),
            PackageSyncCommand.Consume(PACK, dose("2"), INTAKE, tablets("0")).claimAfter
        )
    }

    @Test
    fun consumingNothingIsNotAnIntake() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageSyncCommand.Consume(PACK, dose("0"), INTAKE)
        }
    }

    @Test
    fun packageIsCreatedWithAPositiveStock() {
        // Пачка, которой нет, не заводится — и в доменном сценарии, и в POST-DTO.
        assertThrows(IllegalArgumentException::class.java) {
            PackageSyncCommand.Create(PACK, HOME_KIT, tablets("0"), paracetamol)
        }
    }

    @Test
    fun creationCarriesOnlyWhatCrossesTheBoundary() {
        // Личных сведений в команде нет по типу: срок годности, заметка и цена остаются на
        // устройстве, и «забыть» их в маппере невозможно (PLAN C0).
        val command = PackageSyncCommand.Create(PACK, HOME_KIT, tablets("20"), paracetamol)
        assertEquals(paracetamol, command.facts)
    }

    @Test
    fun describeKeepsBothStates() {
        // Одного «желаемого» не хватило бы: по нему нельзя отличить «не трогали» от «очистили».
        val after = paracetamol.copy(category = "жаропонижающие")
        val command = PackageSyncCommand.Describe(PACK, before = paracetamol, after = after)
        assertEquals(null, command.before.category)
        assertEquals("жаропонижающие", command.after.category)
    }

    @Test
    fun describingNothingIsNotACommand() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageSyncCommand.Describe(PACK, before = paracetamol, after = paracetamol)
        }
    }

    @Test
    fun zeroClaimIsNotAWayToReleaseIt() {
        // Снятие — отдельный вид: на проводе у него другая операция.
        assertThrows(IllegalArgumentException::class.java) {
            PackageSyncCommand.SetClaim(PACK, tablets("0"))
        }
        assertEquals(PACK, PackageSyncCommand.ReleaseClaim(PACK).packageId)
    }
}
