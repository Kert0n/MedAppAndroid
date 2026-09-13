package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.medkit.MedKitProjection
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.medkit.toPresentationDTO

import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.TABLETS_ID
import com.kert0n.medapp.fixture.TABLET_FORM_ID
import com.kert0n.medapp.fixture.factsOf
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.ended
import com.kert0n.medapp.fixture.left
import com.kert0n.medapp.fixture.tablets

import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PresentationStateTest {

    private data class PackageListState(val packages: List<PackagePresentationDTO>)
    private data class MedKitListState(val medKits: List<MedKitPresentationDTO>)

    @Test
    fun stateFlowReceivesConsumptionDescriptionAndTheEndOfTheSamePackage() = runTest {
        val original = pack(quantity = tablets("20"))
        val updates = MutableSharedFlow<List<PackageProjection>>()
        val state = updates.map { packages ->
            PackageListState(packages.map { it.toPresentationDTO() })
        }.stateIn(backgroundScope, SharingStarted.Eagerly, PackageListState(emptyList()))
        runCurrent()

        updates.emit(listOf(original.projected()))
        runCurrent()
        assertEquals("20", state.value.packages.single().quantity.amount)

        val consumed = original.consume(dose("1")).left()
        assertEquals(original, consumed) // Доменное тождество не меняем ради интерфейса.
        assertNotEquals(original.projected(), consumed.projected()) // Наружу уходит проекция, и она различает.
        updates.emit(listOf(consumed.projected()))
        runCurrent()
        assertEquals("19", state.value.packages.single().quantity.amount)

        val edited = consumed.describe(factsOf(consumed).copy(note = "В поездку"))
        updates.emit(listOf(edited.projected()))
        runCurrent()
        assertEquals("В поездку", state.value.packages.single().note)

        // Кончившаяся коробка перестаёт существовать — из списка она уходит целиком (PLAN D3).
        edited.consume(dose("19")).ended()
        updates.emit(emptyList())
        runCurrent()
        assertEquals(emptyList<Any>(), state.value.packages)
    }

    @Test
    fun stateFlowReceivesRenamingAndLocationClearingOfTheSameKit() = runTest {
        val original =
            MedKit(HOME_KIT, "Домашняя", "Шкаф", MedKit.Publication.LOCAL, 1, Instant.EPOCH)
        val updates = MutableSharedFlow<List<MedKitProjection>>()
        val state = updates.map { kits ->
            MedKitListState(kits.map { it.toPresentationDTO() })
        }.stateIn(backgroundScope, SharingStarted.Eagerly, MedKitListState(emptyList()))
        runCurrent()

        updates.emit(listOf(original.projection()))
        runCurrent()
        assertEquals("Домашняя", state.value.medKits.single().name)

        val edited = original.describe("Дачная", null)
        assertEquals(original, edited)
        assertNotEquals(original.projection(), edited.projection())
        updates.emit(listOf(edited.projection()))
        runCurrent()
        assertEquals("Дачная", state.value.medKits.single().name)
        assertNull(state.value.medKits.single().location)
    }

    @Test
    fun numericScaleDoesNotChangePresentationState() {
        val first = pack(
            quantity = tablets("20"), defaultIntakeAmount = dose("1"),
            price = Money(BigDecimal("150")),
            claims = Claims(BigDecimal("5"), BigDecimal("2"))
        )
        val same = pack(
            quantity = tablets("20.000000"), defaultIntakeAmount = dose("1.000000"),
            price = Money(BigDecimal("150.00")),
            claims = Claims(BigDecimal("5.000000"), BigDecimal("2.000000"))
        )
        assertEquals(first.projected().toPresentationDTO(), same.projected().toPresentationDTO())
        assertEquals(first.projected().toPresentationDTO().hashCode(), same.projected().toPresentationDTO().hashCode())
    }

    /**
     * Экран показывает **имя** единицы и формы, а не их номера. Домен различает словарь по
     * тождеству — переименованная единица та же самая (PLAN D1), — поэтому состояние экрана
     * держит свои величины и переименование замечает.
     *
     * Красная проверка: вернуть в DTO доменные `QuantityUnit` и `DosageForm` — оба случая
     * краснеют, состояния оказываются равными.
     */
    @Test
    fun aRenamedUnitChangesThePresentationState() {
        val before = pack(quantity = Quantity(BigDecimal("20"), QuantityUnit(TABLETS_ID, "таблетка")))
        val after = pack(quantity = Quantity(BigDecimal("20"), QuantityUnit(TABLETS_ID, "пилюля")))
        assertEquals(before.quantity.unit, after.quantity.unit)

        assertNotEquals(before.projected().toPresentationDTO(), after.projected().toPresentationDTO())
        assertEquals("пилюля", after.projected().toPresentationDTO().quantity.unit.name)
    }

    @Test
    fun aRenamedFormChangesThePresentationState() {
        val before = pack(form = DosageForm(TABLET_FORM_ID, "таблетки"))
        val after = pack(form = DosageForm(TABLET_FORM_ID, "пилюли"))
        assertEquals(before.facts.form, after.facts.form)

        assertNotEquals(before.projected().toPresentationDTO(), after.projected().toPresentationDTO())
        assertEquals("пилюли", requireNotNull(after.projected().toPresentationDTO().form).name)
    }

    @Test
    fun claimsChangeIsVisibleEvenWithTheSamePackageIdentityAndStock() {
        val before = pack(claims = Claims(BigDecimal("5"), null))
        val after = pack(claims = Claims(BigDecimal("8"), null))
        assertEquals(before, after)
        assertNotEquals(before.projected().toPresentationDTO(), after.projected().toPresentationDTO())
    }
}
