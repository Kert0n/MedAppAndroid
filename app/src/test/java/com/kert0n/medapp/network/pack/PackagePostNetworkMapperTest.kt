package com.kert0n.medapp.network.pack

import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.domain.pack.PackageSharedFacts


import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.kert0n.medapp.fixture.TABLETS_ID
import com.kert0n.medapp.fixture.TABLET_FORM_ID

/**
 * Домен → тело создания пачки. Здесь и заканчивается граница данных: всё, чего нет в
 * `PackagePostNetworkDTO`, остаётся на устройстве (PLAN C0, E5).
 */
class PackagePostNetworkMapperTest {

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
    fun postRejectsEverySpellingOfZero() {
        for (amount in listOf("0", "0.0", "000.000000")) {
            assertThrows(IllegalArgumentException::class.java) {
                onServer.toPostNetworkDTO().copy(amount = amount)
            }
        }
    }

    @Test
    fun postAcceptsTheSmallestPositiveAmount() {
        assertEquals("0.000001", onServer.toPostNetworkDTO().copy(amount = "0.000001").amount)
    }

    @Test
    fun creationCarriesTheServerHalfOnly() {
        val dto = onServer(note = "в машине").toPostNetworkDTO()
        assertEquals("Парацетамол", dto.name)
        assertEquals(TABLET_FORM_ID, dto.formId)
    }

    @Test
    fun quantityBecomesADecimalStringAndAUnitOfItsOwn() {
        // Величина уходит на провод разложенной: строка по шаблону B2 и идентификатор единицы,
        // потому что именно это принимает сервер.
        val dto = onServer().toPostNetworkDTO()
        assertEquals("20", dto.amount)
        assertEquals(TABLETS_ID, dto.unitId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun amountOutsideTheContractIsRejectedByTheRequest() {
        PackagePostNetworkDTO(
            id = PACK,
            name = "Парацетамол",
            amount = "1E+3",
            unitId = TABLETS_ID,
            formId = null,
            category = null,
            manufacturer = null,
            country = null,
            description = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun networkFieldsRefuseAnOverlongDescription() {
        PackagePostNetworkDTO(
            id = PACK,
            name = "Парацетамол",
            amount = "20",
            unitId = TABLETS_ID,
            formId = null,
            category = null,
            manufacturer = null,
            country = null,
            description = "я".repeat(PackageSharedFacts.DESCRIPTION_MAX_LENGTH + 1)
        )
    }
}
