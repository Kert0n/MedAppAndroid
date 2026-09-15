package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.DEFAULT_CURRENCY
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.projected
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.value.ExpiryDatePresentationError
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.presentation.value.toPresentationDTO
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

/** Разбор формы упаковки: строки экрана — в то, что примет сценарий (PLAN H3 №7). */
class PackageFormMapperTest {

    private fun form(
        name: String = "Нурофен",
        amount: String = "20",
        medKitId: Uuid? = HOME_KIT,
        expiresOn: String = "",
        price: String = "",
        hint: String = "",
        note: String = ""
    ) = PackageFormPresentationDTO(
        medKitId = medKitId,
        name = name,
        amount = amount,
        unit = TABLETS.toPresentationDTO(),
        form = TABLET_FORM.toPresentationDTO(),
        expiresOn = expiresOn,
        price = price,
        hintAmount = hint,
        note = note
    )

    @Test
    fun fourFieldsAreEnoughToWriteAPackage() {
        val parsed = form().parsed(VOCABULARY)

        val described = (parsed as ParsedInput.Parsed).value
        assertEquals(HOME_KIT, described.medKitId)
        assertEquals(tablets("20"), described.quantity)
        assertEquals(PackageSharedFacts(name = "Нурофен", form = TABLET_FORM), described.facts.shared)
    }

    /**
     * Количество разбирается первым: без него упаковки не бывает, и человеку важнее узнать про
     * него, чем про слишком длинное описание.
     */
    @Test
    fun theAmountIsCheckedBeforeEverythingOptional() {
        val parsed = form(amount = "", note = "я".repeat(1_000)).parsed(VOCABULARY)

        assertEquals(
            ParsedInput.Rejected(PackageFormError.Amount(QuantityPresentationError.EMPTY)),
            parsed
        )
    }

    /** Пустую упаковку заводить незачем — и это свой отказ, а не «не число». */
    @Test
    fun anEmptyPackageIsNotWorthWriting() {
        assertEquals(ParsedInput.Rejected(PackageFormError.AmountIsZero), form(amount = "0").parsed(VOCABULARY))
    }

    /** Пустые необязательные поля значат «не указано», а не пустую строку. */
    @Test
    fun emptyOptionalFieldsMeanUnknownRatherThanBlank() {
        val described = (form().parsed(VOCABULARY) as ParsedInput.Parsed).value

        assertEquals(null, described.facts.note)
        assertEquals(null, described.facts.price)
        assertEquals(null, described.facts.expiresOn)
        assertEquals(null, described.facts.shared.category)
    }

    /**
     * Срок в прошлом **принимается** и помечается (ТЗ 4.1.2.3): просроченная коробка — та, что
     * лежит дома, а не ошибка ввода.
     *
     * Красная проверка: отвергать прошлое — человек не сможет записать то, что у него есть.
     */
    @Test
    fun anExpiryInThePastIsAccepted() {
        val described = (form(expiresOn = "03.2020").parsed(VOCABULARY) as ParsedInput.Parsed).value

        assertEquals(LocalDate.of(2020, 3, 31), described.facts.expiresOn?.lastDay)
    }

    /** А непонятный срок — отказ со своим полем, а не молчаливое «не указано». */
    @Test
    fun anUnreadableExpiryIsRefusedByItsOwnField() {
        val parsed = form(expiresOn = "когда-нибудь").parsed(VOCABULARY)

        assertEquals(
            ParsedInput.Rejected(PackageFormError.Expiry(ExpiryDatePresentationError.UNKNOWN_FORMAT)),
            parsed
        )
        assertEquals(PackageFormError.Field.EXPIRY, (parsed as ParsedInput.Rejected).error.field)
    }

    /** Аптечку называют всегда: упаковка лежит в каком-то одном месте. */
    @Test
    fun aPackageAlwaysNamesItsShelf() {
        assertEquals(
            ParsedInput.Rejected(PackageFormError.MedKitMissing),
            form(medKitId = null).parsed(VOCABULARY)
        )
    }

    /** Нулевая доза-подсказка — не «не указана», а бессмыслица. */
    @Test
    fun aZeroHintIsNonsenseRatherThanAbsence() {
        assertEquals(ParsedInput.Rejected(PackageFormError.HintIsZero), form(hint = "0").parsed(VOCABULARY))
    }

    /**
     * Правка описания не меняет валюту записанной цены: открытая на правку форма помнит её, и
     * записанное уходит в домен той же валютой. У новой цены — валюта по умолчанию.
     *
     * Красная проверка: собирать цену всегда в рублях — правка одного поля молча переписывала бы
     * долларовую цену в рублёвую.
     */
    @Test
    fun editingKeepsTheCurrencyOfTheStoredPrice() {
        val dollars = Money(BigDecimal("12.50"), Currency.getInstance("USD"))
        val opened = pack(price = dollars).projected().toFormPresentationDTO()
        assertEquals("USD", opened.currency)

        val parsed = opened.copy(note = "в дверце").parsed(VOCABULARY) as ParsedInput.Parsed
        assertEquals(dollars, parsed.value.facts.price)

        val fresh = form(price = "320").parsed(VOCABULARY) as ParsedInput.Parsed
        assertEquals(DEFAULT_CURRENCY, fresh.value.facts.price?.currency)
    }

    /** Карточка справочника, из которой заполнили, доходит до записи: коробка помнит, откуда пришла. */
    @Test
    fun theTemplateTravelsToTheDescription() {
        val id = Uuid.parse("00000000-0000-4000-8000-000000000071")
        val parsed = form().copy(templateId = id).parsed(VOCABULARY) as ParsedInput.Parsed
        assertEquals(id, parsed.value.templateId)
    }
}
