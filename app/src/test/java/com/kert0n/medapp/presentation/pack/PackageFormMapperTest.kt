package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.presentation.value.toPresentationDTO
import java.time.LocalDate
import java.util.Currency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор формы упаковки: строки человека становятся сведениями для домена — или названной
 * причиной, по которой они ими не стали. Исключений тут не бывает ни на каком вводе (REQ-047).
 */
class PackageFormMapperTest {

    private fun form(
        name: String = "Нурофен",
        amount: String = "20",
        unit: Boolean = true,
        block: PackageFormPresentationDTO.() -> PackageFormPresentationDTO = { this }
    ) = PackageFormPresentationDTO(
        medKitId = HOME_KIT,
        name = name,
        amount = amount,
        unit = if (unit) TABLETS.toPresentationDTO() else null
    ).block()

    /** Четырёх полей достаточно: остального человек может не знать (PLAN C1). */
    @Test
    fun fourFieldsAreEnoughToWriteAPackage() {
        val facts = form().parsedFacts(VOCABULARY).valueOrNull

        assertEquals("Нурофен", facts?.name)
        assertEquals(tablets("20"), form().parsedQuantity(VOCABULARY).valueOrNull)
    }

    /**
     * Пустой коробки не бывает: её заводят, чтобы что-то в ней лежало.
     *
     * Красная проверка: пропустить ноль — домен бросит на записи, и форма уронит приложение
     * вместо отказа.
     */
    @Test
    fun anEmptyPackageIsNotWorthWriting() {
        assertEquals(
            PackageFormError.AmountIsZero,
            form(amount = "0").parsedQuantity(VOCABULARY).errorOrNull
        )
    }

    /** Количество без единицы ничего не меряет. */
    @Test
    fun anAmountWithoutAUnitMeasuresNothing() {
        assertEquals(
            PackageFormError.UnitMissing,
            form(unit = false).parsedQuantity(VOCABULARY).errorOrNull
        )
    }

    /** Не число — отказ называет своё поле, а не «где-то ошибка». */
    @Test
    fun somethingThatIsNotANumberNamesItsOwnField() {
        val error = form(amount = "двадцать").parsedQuantity(VOCABULARY).errorOrNull

        assertEquals(PackageFormError.Amount(QuantityPresentationError.NOT_A_DECIMAL), error)
        assertEquals(PackageFormError.Field.AMOUNT, error?.field)
    }

    /** Безымянная упаковка не находится ни поиском, ни глазами. */
    @Test
    fun aPackageWithoutANameIsNotWritten() {
        assertEquals(
            PackageFormError.NameEmpty,
            form(name = "   ").parsedFacts(VOCABULARY).errorOrNull
        )
    }

    /** Пустые необязательные поля значат «не указано», а не пустую строку. */
    @Test
    fun emptyOptionalFieldsMeanNotGiven() {
        val facts = form().parsedFacts(VOCABULARY).valueOrNull

        assertNull(facts?.manufacturer)
        assertNull(facts?.country)
        assertNull(facts?.note)
        assertNull(facts?.price)
        assertNull(facts?.expiresOn)
        assertNull(facts?.defaultIntakeAmount)
    }

    /** Заполненные необязательные доходят до домена все до одного (ТЗ 4.1.1.1). */
    @Test
    fun everyOptionalFieldReachesTheDomain() {
        val filled = form {
            copy(
                category = "жаропонижающее",
                manufacturer = "Reckitt",
                country = "Великобритания",
                description = "ибупрофен 200 мг",
                expiresOn = "03.2027",
                defaultIntakeAmount = "2",
                note = "в дверце",
                price = "320",
                purchasedOn = LocalDate.parse("2026-01-10"),
                openedOn = LocalDate.parse("2026-02-01")
            )
        }

        val facts = filled.parsedFacts(VOCABULARY).valueOrNull!!
        assertEquals("жаропонижающее", facts.category)
        assertEquals("Reckitt", facts.manufacturer)
        assertEquals("Великобритания", facts.country)
        assertEquals("ибупрофен 200 мг", facts.description)
        assertEquals(expiry("2027-03-31"), facts.expiresOn)
        assertEquals(Dose(tablets("2")), facts.defaultIntakeAmount)
        assertEquals("в дверце", facts.note)
        assertEquals(Currency.getInstance("RUB"), facts.price?.currency)
        assertEquals(LocalDate.parse("2026-01-10"), facts.purchasedOn)
        assertEquals(LocalDate.parse("2026-02-01"), facts.openedOn)
    }

    /** Срок в прошлом принимается: ТЗ 4.1.2 требует принимать «реалистично некорректное». */
    @Test
    fun anExpiryInThePastIsAccepted() {
        val facts = form { copy(expiresOn = "03.2020") }.parsedFacts(VOCABULARY).valueOrNull

        assertEquals(expiry("2020-03-31"), facts?.expiresOn)
    }

    /** Слишком длинное поле называет себя, а предел держит тип сведений. */
    @Test
    fun aFieldThatIsTooLongNamesItself() {
        val long = "я".repeat(PackageSharedFacts.COUNTRY_MAX_LENGTH + 1)

        assertEquals(
            PackageFormError.TooLong(PackageFormError.Field.COUNTRY),
            form { copy(country = long) }.parsedFacts(VOCABULARY).errorOrNull
        )
    }

    /** Нулевая доза-подсказка — не доза (PLAN D1). */
    @Test
    fun aHintOfZeroIsNotADose() {
        assertEquals(
            PackageFormError.HintIsZero,
            form { copy(defaultIntakeAmount = "0") }.parsedFacts(VOCABULARY).errorOrNull
        )
    }

    /** Открытая на правку форма показывает записанное, и разбор возвращает то же самое. */
    @Test
    fun whatWasStoredComesBackUnchanged() {
        val stored = pack(
            quantity = tablets("20"),
            expiresOn = expiry("2027-03-31")
        ).projected()

        val reparsed = stored.toFormPresentationDTO().parsedFacts(VOCABULARY).valueOrNull

        assertEquals(stored.facts, reparsed)
    }
}
