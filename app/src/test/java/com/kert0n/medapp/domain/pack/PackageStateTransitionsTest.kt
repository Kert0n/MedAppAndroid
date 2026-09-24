package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.factsOf
import com.kert0n.medapp.fixture.left
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.withShared
import java.math.BigDecimal
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Переходы, меняющие сведения и принадлежность. Состояний жизни у коробки нет: она либо есть, либо
 * её нет, и утрата доступа — запись в истории о том, что из учёта ушло (PLAN D3, D7). Статус говорит
 * только о решении, которое ещё не подтверждено, и помеченной к уходу коробкой не пользуются (E1).
 */
class PackageStateTransitionsTest {

    /** Команда, которая ставит пометку: её закрытие — единственное, что пометку снимает (E1). */
    private val DECISION: Uuid = Uuid.parse("00000000-0000-4000-8000-0000000000d1")

    @Test
    fun editReplacesTheWholeDescriptiveState() {
        val described = pack().describe(
            factsOf(pack())
                .withShared(name = "Парацетамол-Дарница", category = "жаропонижающие")
                .copy(
                    expiresOn = expiry("2027-03-31"),
                    note = "в машине",
                    price = Money(BigDecimal("120.00"))
                )
        )
        assertEquals("Парацетамол-Дарница", described.name)
        assertEquals("жаропонижающие", described.facts.category)
        assertEquals(expiry("2027-03-31"), described.facts.expiresOn)
        assertEquals("в машине", described.facts.note)
        assertEquals(Money(BigDecimal("120.00")), described.facts.price)
    }

    @Test
    fun editClearsWhatWasCleared() {
        val filled = pack(category = "жаропонижающие", expiresOn = expiry("2027-03-31"))
        val emptied = factsOf(filled).withShared(category = null).copy(expiresOn = null)
        val cleared = filled.describe(emptied)
        assertNull(cleared.facts.category)
        assertNull(cleared.facts.expiresOn)
    }

    @Test
    fun editDoesNotTouchQuantityOrOwnership() {
        val moved = pack(quantity = tablets("20"))
        val described = moved.describe(factsOf(moved).withShared(name = "другое"))
        assertEquals(tablets("20"), described.quantity)
        assertEquals(HOME_KIT, described.medKit.id)
    }

    @Test
    fun movingChangesOnlyTheKit() {
        val moved = pack(claims = Claims(BigDecimal("5"))).moveTo(medKit(id = SHARED_KIT, name = "Общая").ref)
        assertEquals(SHARED_KIT, moved.medKit.id)
        assertEquals(BigDecimal("5"), moved.claims?.total)
    }

    @Test(expected = IllegalArgumentException::class)
    fun movingIntoTheSameKitIsRefused() {
        pack(medKit = medKit(id = HOME_KIT).ref).moveTo(medKit(id = HOME_KIT).ref)
    }

    @Test
    fun aShelfBeingRemovedTakesNothingWhileAPublishedOneStillDoes() {
        // Убираемая полка вот-вот уйдёт: положенная в неё коробка уехала бы с ней, ничего человеку
        // не сказав. Публикуемой это не касается — ею пользуются, пока сервер её заводит (E5, E6).
        val removing = medKit(id = SHARED_KIT, name = "Общая", status = MedKitStatus.REMOVING).ref
        assertThrows(IllegalStateException::class.java) { pack().moveTo(removing) }
        val publishing = medKit(id = SHARED_KIT, name = "Общая", status = MedKitStatus.PUBLISHING).ref
        assertEquals(SHARED_KIT, pack().moveTo(publishing).medKit.id)
    }

    @Test
    fun theAnswerOfAShelfCarriesEvenAMarkedBoxAndKeepsItsMark() {
        // Переезд по ответу полки — не пользование: ни своя пометка коробки, ни пометка полки его
        // не отменяют, иначе ответ было бы нечем применить (PLAN E1, E6).
        val shared = pack(medKit = medKit(id = SHARED_KIT, name = "Общая").ref)
        val home = medKit(id = HOME_KIT, status = MedKitStatus.REMOVING).ref
        val carried = shared.markChanging(DECISION).movedByAnswer(home)
        assertEquals(HOME_KIT, carried.medKit.id)
        assertEquals(PackageStatus.CHANGING, carried.status)
        assertEquals(PackageStatus.REMOVING, shared.markRemoving(DECISION).movedByAnswer(home).status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun theAnswerDoesNotMoveABoxIntoTheShelfItAlreadyLiesIn() {
        pack(medKit = medKit(id = HOME_KIT).ref).movedByAnswer(medKit(id = HOME_KIT).ref)
    }

    /**
     * Унесли при 20, дома выпили одну — у нас 19; полка к снятию подтвердила 17: сосед выпил три.
     * Коробка хранит оба изменения — 16 (PLAN E6).
     */
    @Test
    fun aBoxCarriedHomeKeepsBothTheShelfsAndItsOwnChanges() {
        val atHome = pack(quantity = tablets("19"))
        assertEquals(tablets("16"), atHome.rebased(from = tablets("20"), onto = tablets("17")).left().quantity)
        assertTrue(atHome.rebased(from = tablets("20"), onto = tablets("1")) is PackageAfter.Ended)
    }

    @Test
    fun aBoxMarkedToGoIsReadOnly() {
        for (marked in listOf(pack().markRemoving(DECISION), pack().markLost(DECISION))) {
            assertTrue(marked.take(Dose(tablets("1")), LATER).isFailure)
            assertThrows(IllegalStateException::class.java) { marked.dispose(tablets("1")) }
            assertThrows(IllegalStateException::class.java) { marked.correctTo(tablets("5")) }
            assertThrows(IllegalStateException::class.java) { marked.describe(factsOf(marked)) }
            assertThrows(IllegalStateException::class.java) { marked.moveTo(medKit(id = SHARED_KIT).ref) }
        }
    }

    @Test
    fun aBoxBeingChangedIsStillInUseAndTheAnswerSettlesIt() {
        val changing = pack().markChanging(DECISION)
        assertEquals(PackageStatus.CHANGING, changing.status)
        assertTrue(changing.take(Dose(tablets("1")), LATER).isSuccess)
        assertEquals(PackageStatus.ACTIVE, changing.settledBy(DECISION).status)
    }

    /**
     * Пометку снимает только та команда, которая её поставила: чужая закрытая команда оставляет
     * решение в силе (PLAN E1).
     *
     * Красная проверка: пока пометку снимало закрытие любой команды коробки, доехавшая старая бронь
     * возвращала в оборот коробку, которую полка уже решила выбросить.
     */
    @Test
    fun onlyTheCommandThatMarkedTheBoxReleasesIt() {
        val marked = pack().markRemoving(DECISION)
        assertEquals(PackageStatus.REMOVING, marked.settledBy(Uuid.random()).status)
        assertEquals(PackageStatus.ACTIVE, marked.settledBy(DECISION).status)
        assertNull(marked.settledBy(DECISION).decidedBy)
    }

    /** Пометки без своей команды не бывает: такое состояние не выражается (PLAN E1). */
    @Test
    fun aMarkWithoutItsCommandIsNotExpressible() {
        assertThrows(IllegalArgumentException::class.java) {
            pack().let { Package(it.id, it.medKit, it.facts, it.quantity, it.addedAt, status = PackageStatus.REMOVING) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            pack().let { Package(it.id, it.medKit, it.facts, it.quantity, it.addedAt, decidedBy = Uuid.random()) }
        }
    }

    @Test
    fun theEndOfAMarkedBoxIsNotRefused() {
        // Конец — ответ на решение, а не пользование: помеченная коробка обязана уметь кончиться.
        assertEquals(pack().id, pack().markRemoving(DECISION).ended().record.id)
        assertEquals(pack().id, pack().markLost(DECISION).ended().record.id)
    }
}
