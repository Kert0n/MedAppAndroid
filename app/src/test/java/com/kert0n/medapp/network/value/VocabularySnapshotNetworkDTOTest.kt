package com.kert0n.medapp.network.value

import com.kert0n.medapp.network.server.medAppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Встроенный снимок словарей несёт своё происхождение и читается только в известном формате:
 * чужая версия, неверная дата и дважды заведённая запись отвергаются, а не засеваются молча.
 */
class VocabularySnapshotNetworkDTOTest {

    private val unit = "00000000-0000-4000-8000-000000000031"
    private val form = "00000000-0000-4000-8000-000000000041"

    private fun snapshot(
        version: Int = 1,
        capturedOn: String = "2026-09-10",
        units: String = """[{"id":"$unit","name":"таб"}]"""
    ) = """
        {"origin":"production","capturedOn":"$capturedOn","version":$version,
         "quantityUnits":$units,"formTypes":[{"id":"$form","name":"таблетки"}]}
    """

    private fun read(json: String) =
        medAppJson.decodeFromString(VocabularySnapshotNetworkDTO.serializer(), json)

    @Test
    fun snapshotCarriesItsOrigin() {
        val read = read(snapshot())

        assertEquals("production", read.origin)
        assertEquals("2026-09-10", read.capturedOn)
        assertEquals("таб", read.quantityUnits.single().toQuantityUnit().name)
        assertEquals("таблетки", read.formTypes.single().toDosageForm().name)
    }

    @Test
    fun unknownFormatIsNotRead() {
        assertThrows(IllegalArgumentException::class.java) { read(snapshot(version = 2)) }
    }

    @Test
    fun captureDateIsAnIsoDate() {
        assertThrows(IllegalArgumentException::class.java) { read(snapshot(capturedOn = "10.09.2026")) }
    }

    @Test
    fun entryIsNotBundledTwice() {
        val twice = """[{"id":"$unit","name":"таб"},{"id":"$unit","name":"таблетка"}]"""

        assertThrows(IllegalArgumentException::class.java) { read(snapshot(units = twice)) }
    }
}
