package com.kert0n.medapp.feature.value

import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import kotlinx.coroutines.flow.Flow

/** Что экрану нужно от словаря: единицы и формы потоком и нынешний снимок. Объявляет сценарий, исполняет хранение. */
interface VocabularyReadings {

    /** Словарь, какой он у устройства сейчас: чтобы экран разобрал введённое по известным единицам. */
    suspend fun snapshot(): Vocabulary

    fun observeUnits(): Flow<List<QuantityUnit>>

    fun observeForms(): Flow<List<DosageForm>>
}
