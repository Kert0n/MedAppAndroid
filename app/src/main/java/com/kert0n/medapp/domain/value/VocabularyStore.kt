package com.kert0n.medapp.domain.value

/**
 * Где лежит снимок словаря на устройстве. Нужен тому, кто словарь дочитывает с сервера, а
 * исполняет его хранение — поэтому объявлен здесь, рядом с [VocabularyLibrary]: адаптеры друг
 * друга не видят.
 */
interface VocabularyStore {

    suspend fun snapshot(): Vocabulary

    /** Записи переименовываются и добавляются, но не удаляются: словарь не убывает. */
    suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>)
}
