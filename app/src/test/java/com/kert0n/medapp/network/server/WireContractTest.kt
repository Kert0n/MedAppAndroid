package com.kert0n.medapp.network.server

import com.kert0n.medapp.network.account.AccessTokenNetworkDTO
import com.kert0n.medapp.network.account.AccountPostNetworkDTO
import com.kert0n.medapp.network.account.AccountSnapshotNetworkDTO
import com.kert0n.medapp.network.medkit.InvitationNetworkDTO
import com.kert0n.medapp.network.medkit.MedKitSummaryNetworkDTO
import com.kert0n.medapp.network.pack.ClaimPostNetworkDTO
import com.kert0n.medapp.network.pack.PackagePatchNetworkDTO
import com.kert0n.medapp.network.pack.PackagePostNetworkDTO
import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.pack.PackageSyncNetworkDTO
import com.kert0n.medapp.network.template.PackageTemplateNetworkDTO
import com.kert0n.medapp.network.value.VocabularyEntryNetworkDTO
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Провод совпадает с `open-api.yaml` сервера: имена полей, строки количеств, версии числом.
 * Разбор строгий — неизвестное поле роняет тест, а не теряется молча (PLAN H2, J1).
 */
class WireContractTest {

    private val pack = "00000000-0000-4000-8000-000000000011"
    private val kit = "00000000-0000-4000-8000-000000000021"
    private val unit = "00000000-0000-4000-8000-000000000031"
    private val form = "00000000-0000-4000-8000-000000000041"

    private fun drug(extra: String = "") = """
        {"id":"$pack","name":"Аспирин","quantity":"100.000000","quantityUnitId":"$unit",
         "formTypeId":"$form","category":null,"manufacturer":"Bayer","country":null,
         "description":null,"medKitId":"$kit","version":3$extra}
    """

    private fun snapshot(drug: String = drug(), mine: String = "null") =
        """{"drug":$drug,"reservations":{"total":"40.000000","mine":$mine,"version":5}}"""

    private fun <T> read(serializer: KSerializer<T>, json: String): T =
        medAppJson.decodeFromString(serializer, json)

    private fun <T> written(serializer: KSerializer<T>, value: T): JsonElement =
        medAppJson.encodeToJsonElement(serializer, value)

    private fun json(text: String): JsonElement = medAppJson.parseToJsonElement(text)

    @Test
    fun packageSnapshotIsReadUnderServerNames() {
        val read = read(PackageSnapshotNetworkDTO.serializer(), snapshot(mine = "\"20.000000\""))

        assertEquals(Uuid.parse(pack), read.pack.id)
        assertEquals("100.000000", read.pack.amount)
        assertEquals(Uuid.parse(unit), read.pack.unitId)
        assertEquals(Uuid.parse(form), read.pack.formId)
        assertEquals(ResourceVersionNetworkDTO(3), read.pack.version)
        assertEquals("40.000000", read.claims.total)
        assertEquals("20.000000", read.claims.mine)
        assertEquals(ResourceVersionNetworkDTO(5), read.claims.version)
    }

    @Test
    fun absentAndNullOptionalFieldsBothReadAsNothing() {
        val withoutKeys = """
            {"id":"$pack","name":"Аспирин","quantity":"1.000000","quantityUnitId":"$unit",
             "medKitId":"$kit","version":0}
        """
        val read = read(PackageSnapshotNetworkDTO.serializer(), snapshot(drug = withoutKeys))

        assertNull(read.pack.formId)
        assertNull(read.pack.category)
        assertNull(read.claims.mine)
    }

    @Test
    fun unknownFieldIsAContractBreakNotASilentLoss() {
        assertThrows(IllegalArgumentException::class.java) {
            read(PackageSnapshotNetworkDTO.serializer(), snapshot(drug = drug(""","expiresOn":null""")))
        }
    }

    @Test
    fun negativeVersionIsNotAVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            read(PackageSnapshotNetworkDTO.serializer(), snapshot(drug = drug().replace("\"version\":3", "\"version\":-1")))
        }
    }

    @Test
    fun amountOutsideB2IsRejectedOnTheWayIn() {
        assertThrows(IllegalArgumentException::class.java) {
            read(PackageSnapshotNetworkDTO.serializer(), snapshot(drug = drug().replace("100.000000", "1e2")))
        }
    }

    @Test
    fun accountSnapshotCarriesKitsWithTheirPackages() {
        val read = read(
            AccountSnapshotNetworkDTO.serializer(),
            """{"id":"$kit","medKits":[{"id":"$kit","userCount":2,"drugs":[${snapshot()}]}]}"""
        )

        val medKit = read.medKits.single()
        assertEquals(2L, medKit.participantCount)
        assertEquals(Uuid.parse(pack), medKit.packages.single().pack.id)
    }

    @Test
    fun summaryNamesPackagesByIdentifierOnly() {
        val read = read(
            MedKitSummaryNetworkDTO.serializer(),
            """{"id":"$kit","userCount":1,"drugIds":["$pack"]}"""
        )
        assertEquals(listOf(Uuid.parse(pack)), read.packageIds)
    }

    @Test
    fun emptyVocabularyIsAnEmptyList() {
        val entries = ListSerializer(VocabularyEntryNetworkDTO.serializer())
        assertEquals(emptyList<VocabularyEntryNetworkDTO>(), read(entries, "[]"))
        assertEquals("мг", read(entries, """[{"id":"$unit","name":"мг"}]""").single().name)
    }

    @Test
    fun catalogueEntryIsReadWithoutQuantityOrExpiry() {
        val read = read(
            PackageTemplateNetworkDTO.serializer(),
            """
            {"id":"$pack","name":"Аспирин","nameLat":"Aspirin","activeSubstance":null,
             "formTypeId":"$form","category":null,"quantityUnitId":"$unit",
             "manufacturer":"Bayer","country":"Германия","description":null}
            """
        )
        assertEquals(Uuid.parse(form), read.formId)
        assertEquals(Uuid.parse(unit), read.unitId)
    }

    @Test
    fun creationSendsTheClientIdentifierAndLeavesOutAbsentFields() {
        val dto = PackagePostNetworkDTO(
            id = Uuid.parse(pack),
            name = "Аспирин",
            amount = "10",
            unitId = Uuid.parse(unit),
            formId = null,
            category = null,
            manufacturer = "Bayer",
            country = null,
            description = null
        )

        assertEquals(
            json("""{"id":"$pack","name":"Аспирин","quantity":"10","quantityUnitId":"$unit","manufacturer":"Bayer"}"""),
            written(PackagePostNetworkDTO.serializer(), dto)
        )
    }

    @Test
    fun patchSendsOnlyTouchedFieldsWithItsVersion() {
        val dto = PackagePatchNetworkDTO(
            name = "Аспирин C",
            description = "",
            version = ResourceVersionNetworkDTO(3)
        )

        assertEquals(
            json("""{"name":"Аспирин C","description":"","version":3}"""),
            written(PackagePatchNetworkDTO.serializer(), dto)
        )
        assertTrue(PackagePatchNetworkDTO(version = ResourceVersionNetworkDTO(3)).isEmpty)
    }

    @Test
    fun syncWithoutClaimChangeLeavesTheReservationOut() {
        val dto = PackageSyncNetworkDTO(consumed = "2", packageVersion = ResourceVersionNetworkDTO(3))

        assertEquals(
            json("""{"consumed":"2","drugVersion":3}"""),
            written(PackageSyncNetworkDTO.serializer(), dto)
        )
    }

    @Test
    fun offlineConsumptionNamesThePackageVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageSyncNetworkDTO(consumed = "2")
        }
    }

    @Test
    fun claimCommandsNameThePackageTheWayTheServerDoes() {
        val dto = ClaimPostNetworkDTO(Uuid.parse(pack), "20", ResourceVersionNetworkDTO(5))

        assertEquals(
            json("""{"drugId":"$pack","amount":"20","version":5}"""),
            written(ClaimPostNetworkDTO.serializer(), dto)
        )
    }

    @Test
    fun zeroIsNotAClaimInAnySpelling() {
        for (amount in listOf("0", "0.0", "000.000000")) {
            assertThrows(IllegalArgumentException::class.java) {
                ClaimPostNetworkDTO(Uuid.parse(pack), amount)
            }
        }
    }

    @Test
    fun credentialsAreReadButNeverPrinted() {
        val invented = AccountPostNetworkDTO(Uuid.parse(kit), "k3y-shown-only-once-43-characters-long-abcd")
        val token = read(AccessTokenNetworkDTO.serializer(), """{"accessToken":"jwt-secret"}""")
        val invitation = read(InvitationNetworkDTO.serializer(), """{"key":"invitation-secret"}""")

        assertEquals(
            json("""{"login":"$kit","password":"k3y-shown-only-once-43-characters-long-abcd"}"""),
            written(AccountPostNetworkDTO.serializer(), invented)
        )
        assertFalse(invented.toString().contains("k3y-shown-only-once"))
        assertFalse(token.toString().contains("jwt-secret"))
        assertFalse(invitation.toString().contains("invitation-secret"))
    }
}
