package com.kert0n.medapp.network.value

import com.kert0n.medapp.domain.attempt
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * Встроенный снимок словарей: единицы и формы, снятые с сервера заранее, чтобы они были у
 * приложения сразу после регистрации, ещё до первого чтения. Рядом записано происхождение —
 * откуда и когда снято: идентификаторы серверные, и сверять их придётся с тем же сервером. У
 * встроенного снимка происхождение — метка `production`, а не адрес (`scripts/capture-vocabulary.sh`).
 */
@Serializable
data class VocabularySnapshotNetworkDTO(
    val origin: String,
    val capturedOn: String,
    val version: Int,
    val quantityUnits: List<VocabularyEntryNetworkDTO>,
    val formTypes: List<VocabularyEntryNetworkDTO>
) {
    init {
        require(version == FORMAT_VERSION) { "формат снимка словарей $version этой сборкой не читается" }
        require(attempt { LocalDate.parse(capturedOn) }.isSuccess) {
            "дата снятия словарей — ISO-дата, а не «$capturedOn»"
        }
        require(quantityUnits.distinctBy { it.id }.size == quantityUnits.size) {
            "единица в снимке словарей заведена дважды"
        }
        require(formTypes.distinctBy { it.id }.size == formTypes.size) {
            "форма в снимке словарей заведена дважды"
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val ASSET = "vocabulary.json"
    }
}
