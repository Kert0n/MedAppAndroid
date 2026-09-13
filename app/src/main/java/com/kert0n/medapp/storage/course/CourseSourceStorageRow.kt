package com.kert0n.medapp.storage.course

import androidx.room.Embedded
import androidx.room.Relation
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.storage.pack.PackageRecordStorageEntity
import com.kert0n.medapp.storage.pack.PackageRefStorageRow

/**
 * Источник курса вместе со ссылкой на свою пачку: Room читает её той же транзакцией, что и
 * курс, — одним запросом на все источники, а не по запросу на строку.
 */
class CourseSourceStorageRow(
    @Embedded val source: CourseSourceStorageEntity,
    @Relation(entity = PackageRecordStorageEntity::class, parentColumn = "package_id", entityColumn = "id")
    val pack: PackageRefStorageRow? = null
) {
    fun toDomain(vocabulary: Vocabulary): CourseSource = CourseSource(
        pkg = requireNotNull(pack) { "источник курса ссылается на пачку, которой нет: ${source.packageId}" }
            .toRef(vocabulary),
        allocatedDoses = Doses(source.allocatedDoses)
    )
}
