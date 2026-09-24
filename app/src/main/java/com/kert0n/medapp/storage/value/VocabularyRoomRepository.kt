package com.kert0n.medapp.storage.value

import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.domain.value.VocabularyStore
import com.kert0n.medapp.feature.value.VocabularyReadings
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class VocabularyRoomRepository @Inject constructor(
    private val vocabulary: VocabularyDao
) : VocabularyReadings, VocabularyStore {

    override fun observeUnits(): Flow<List<QuantityUnit>> =
        vocabulary.observeUnits().map { rows -> rows.map { it.toDomain() } }

    override fun observeForms(): Flow<List<DosageForm>> =
        vocabulary.observeForms().map { rows -> rows.map { it.toDomain() } }

    override suspend fun snapshot(): Vocabulary = vocabulary.snapshot()

    override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) =
        vocabulary.save(units.map { it.toStorageEntity() }, forms.map { it.toStorageEntity() })
}
