package com.kert0n.medapp.storage.pack

import androidx.room.Embedded
import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.value.Vocabulary

/**
 * Пачка глазами чужого агрегата — источника курса, приёма: запись о коробке, а не живая
 * строка, поэтому история читается и после того, как коробки не стало (PLAN D6). Room
 * читает её связью той же транзакцией, что и владельца, одним запросом на всю выборку; в домен
 * уходит [PackageRef].
 */
class PackageRefStorageRow(
    @Embedded val record: PackageRecordStorageEntity
) {
    fun toRef(vocabulary: Vocabulary): PackageRef = record.toRef(vocabulary)
}
