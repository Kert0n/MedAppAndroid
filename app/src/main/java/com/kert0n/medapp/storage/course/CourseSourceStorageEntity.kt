package com.kert0n.medapp.storage.course

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.kert0n.medapp.domain.course.CourseMedicine
import com.kert0n.medapp.storage.pack.PackageStorageEntity
import kotlin.uuid.Uuid

/**
 * Источник курса: порядок — приоритет расходования, поэтому позиция хранится числом, а
 * уникальность пары «курс и позиция» ловит сбой перетаскивания (PLAN F1, D5).
 *
 * Выделение хранится в дозах, а не в количестве: доза курса своя, и пересчёт в единицы пачки —
 * дело курса.
 *
 * Ключ на пачку — `RESTRICT`, потому что это состав **курса**, а курс охраняет его своей редакцией.
 * Каскад убрал бы источник молча: набор строк получился бы верный, а редакция осталась бы прежней,
 * и тот, кто читал курс до этого, не узнал бы, что состав уже другой. Снимает источник доменный
 * переход, и только он (PLAN D5, F2).
 */
@Entity(
    tableName = "course_sources",
    primaryKeys = ["course_id", "package_id"],
    foreignKeys = [
        ForeignKey(
            entity = CourseStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["course_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PackageStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["package_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["course_id", "position"], unique = true),
        Index("package_id")
    ]
)
class CourseSourceStorageEntity(
    @ColumnInfo(name = "course_id") val courseId: Uuid,
    @ColumnInfo(name = "package_id") val packageId: Uuid,
    val position: Int,
    @ColumnInfo(name = "allocated_doses") val allocatedDoses: Int
)

fun CourseMedicine.toSourceStorageEntities(courseId: Uuid): List<CourseSourceStorageEntity> =
    sources.mapIndexed { position, source ->
        CourseSourceStorageEntity(
            courseId = courseId,
            packageId = source.pkg.id,
            position = position,
            allocatedDoses = source.allocatedDoses.count
        )
    }
