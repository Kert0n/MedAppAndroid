package com.kert0n.medapp.network.value

import com.kert0n.medapp.network.server.medAppJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Снимок, который уезжает в APK, читается строгим форматом провода, снят с боевого сервера и
 * засевается доменными величинами без отказа: сломанный снимок уронил бы создание базы.
 */
class BundledVocabularyAssetTest {

    private val snapshot = medAppJson.decodeFromString(
        VocabularySnapshotNetworkDTO.serializer(),
        File("src/main/assets/${VocabularySnapshotNetworkDTO.ASSET}").readText()
    )

    @Test
    fun snapshotComesFromTheProductionServer() {
        assertEquals("production", snapshot.origin)
    }

    @Test
    fun bothVocabulariesArePresentAndValid() {
        assertTrue(snapshot.quantityUnits.isNotEmpty())
        assertTrue(snapshot.formTypes.isNotEmpty())
        snapshot.quantityUnits.forEach { it.toQuantityUnit() }
        snapshot.formTypes.forEach { it.toDosageForm() }
    }
}
