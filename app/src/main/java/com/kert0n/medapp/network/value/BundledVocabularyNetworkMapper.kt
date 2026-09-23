package com.kert0n.medapp.network.value

import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.server.medAppJson

/**
 * Встроенный снимок словаря — это ответ сервера, снятый заранее (`scripts/capture-vocabulary.sh`),
 * и разбирается он той же формой провода: провод → домен.
 */
fun bundledVocabulary(json: String): Vocabulary {
    val snapshot = medAppJson.decodeFromString(VocabularySnapshotNetworkDTO.serializer(), json)
    return Vocabulary(snapshot.quantityUnits.map { it.toQuantityUnit() }, snapshot.formTypes.map { it.toDosageForm() })
}
