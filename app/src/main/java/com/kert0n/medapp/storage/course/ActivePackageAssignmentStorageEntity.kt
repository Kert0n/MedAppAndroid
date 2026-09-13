package com.kert0n.medapp.storage.course

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kert0n.medapp.storage.pack.PackageStorageEntity
import kotlin.uuid.Uuid

/**
 * «Одна пачка — один активный курс» держится этой таблицей, а не проверкой перед вставкой:
 * два экрана записали бы одновременно и оба увидели бы пусто. Первичный ключ по пачке отвергает
 * второе назначение сам (PLAN F1, F2).
 *
 * Строка появляется при активации и исчезает при завершении, отмене и отвязке. Черновик пачку
 * не занимает.
 *
 * Ключ на пачку — `RESTRICT`: занятость пачки снимает курс, а не схема. Каскад освобождал бы её
 * молча, мимо редакции курса, которая эту занятость и охраняет (PLAN D5, F2).
 */
@Entity(
    tableName = "active_package_assignments",
    foreignKeys = [
        ForeignKey(
            entity = PackageStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["package_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CourseStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["course_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("course_id")]
)
class ActivePackageAssignmentStorageEntity(
    @PrimaryKey @ColumnInfo(name = "package_id") val packageId: Uuid,
    @ColumnInfo(name = "course_id") val courseId: Uuid
)
