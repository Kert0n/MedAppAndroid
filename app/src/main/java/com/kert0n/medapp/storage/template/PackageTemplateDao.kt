package com.kert0n.medapp.storage.template

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PackageTemplateDao {

    /** Ответ сервера ложится поверх прежних карточек тех же идентификаторов. */
    @Upsert
    suspend fun upsert(templates: List<PackageTemplateStorageEntity>)

    /** [text] — уже в нижнем регистре; свежие карточки первыми, при равенстве — по названию. */
    @Query(
        """
        SELECT * FROM drug_templates
        WHERE search_text LIKE '%' || :text || '%'
        ORDER BY cached_at DESC, name
        LIMIT :limit
        """
    )
    suspend fun search(text: String, limit: Int): List<PackageTemplateStorageEntity>
}
