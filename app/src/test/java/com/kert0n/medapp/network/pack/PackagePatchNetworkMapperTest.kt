package com.kert0n.medapp.network.pack

import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.CAPSULE_FORM_ID
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.factsOf
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.withShared
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.pack.PackageSyncState
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Домен → намерение PATCH. В домене `null` значит «сведений нет», на проводе — «не трогать»,
 * а очистка там выражается пустой строкой (PLAN D3, H2). Весь перевод живёт здесь.
 */
class PackagePatchNetworkMapperTest {

    private fun onServer(note: String? = null) = pack(
        name = "Парацетамол",
        form = TABLET_FORM,
        category = "жаропонижающие",
        description = "по одной при температуре",
        note = note
    )

    private val onServer = onServer()

    /** Пачка уже создана на сервере: у неё есть предусловие. */
    private val synced = PackageSyncState(packageId = PACK, version = ResourceVersion(7))

    /** Пачка ещё только заводится: предусловия нет. */
    private val notSynced = PackageSyncState(packageId = PACK)

    @Test
    fun unchangedFormSendsNothing() {
        // PATCH теми же значениями перетёр бы чужую правку, которую мы даже не видели.
        val patch = factsOf(onServer).toPatchNetworkMapping(onServer, synced)
        assertNull(patch.dto)
        assertFalse(patch.formIdClearUnsupported)
    }

    @Test
    fun localOnlyEditIsAnsweredByTheComposition() {
        // «Правка была только локальной?» — это вопрос к структуре сведений, а не сравнение шести
        // полей россыпью, где седьмое забудут (PLAN E2, D3).
        val localEdit = factsOf(onServer).copy(note = "в машине", expiresOn = expiry("2027-03-31"))
        assertEquals(factsOf(onServer).shared, localEdit.shared)
        assertNull(localEdit.toPatchNetworkMapping(onServer, synced).dto)
    }

    @Test
    fun onlyTheChangedFieldTravels() {
        val renamed = factsOf(onServer).withShared(name = "Парацетамол-Дарница")
        val patch = renamed.toPatchNetworkMapping(onServer, synced)
        val dto = requireNotNull(patch.dto)
        assertEquals("Парацетамол-Дарница", dto.name)
        assertNull(dto.category)
        assertNull(dto.description)
    }

    @Test
    fun clearedTextTravelsAsAnEmptyString() {
        val patch = factsOf(onServer).withShared(description = null)
            .toPatchNetworkMapping(onServer, synced)
        assertEquals("", requireNotNull(patch.dto).description)
    }

    @Test
    fun clearingTheFormOfAServerPackIsReportedInsteadOfSentAsNull() {
        // `null` на проводе значит «не менять», а `""` не является UUID: молча выдать
        // неудалённую серверную форму за очищенную нельзя.
        val patch = factsOf(onServer).withShared(form = null).toPatchNetworkMapping(onServer, synced)
        assertTrue(patch.formIdClearUnsupported)
        assertNull(patch.dto?.formId)
    }

    @Test
    fun clearingTheFormOfAPackNotYetOnTheServerIsFine() {
        // Ограничение — протокольное, поэтому зависит от предусловия, а не от самой пачки.
        val patch = factsOf(onServer).withShared(form = null)
            .toPatchNetworkMapping(onServer, notSynced)
        assertFalse(patch.formIdClearUnsupported)
    }

    @Test
    fun changingTheFormToAnotherOneTravels() {
        val patch = factsOf(onServer).withShared(form = CAPSULE_FORM)
            .toPatchNetworkMapping(onServer, synced)
        assertEquals(CAPSULE_FORM_ID, requireNotNull(patch.dto).formId)
        assertFalse(patch.formIdClearUnsupported)
    }

    @Test
    fun localOnlyFieldsNeverReachTheNetwork() {
        // Срок годности, заметка, цена и даты остаются только на устройстве (PLAN C0, E5).
        val patch = factsOf(onServer)
            .copy(note = "в машине", price = Money(BigDecimal("120.00")))
            .toPatchNetworkMapping(onServer, synced)
        assertNull(patch.dto)
    }

    @Test(expected = IllegalArgumentException::class)
    fun networkEditRefusesToClearTheName() {
        PackagePatchNetworkDTO(name = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun networkEditRefusesWhitespaceThatIsNeitherValueNorClearing() {
        PackagePatchNetworkDTO(description = "   ")
    }

    @Test
    fun networkEditAcceptsEmptyStringAsClearing() {
        assertEquals("", PackagePatchNetworkDTO(description = "").description)
    }

}
