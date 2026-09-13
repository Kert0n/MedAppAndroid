package com.kert0n.medapp.storage.pack

import androidx.room.ColumnInfo
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.storage.value.storedDose
import com.kert0n.medapp.storage.value.storedUnit
import kotlin.uuid.Uuid

/**
 * Сколько этой пачки занято активным курсом — и каким. Пачка входит не больше чем в один активный
 * курс, поэтому строка на пачку одна, а перевод доз в единицы пачки делает доза курса (PLAN D5).
 */
class PackageAllocationRow(
    @ColumnInfo(name = "package_id") val packageId: Uuid,
    @ColumnInfo(name = "course_id") val courseId: Uuid,
    @ColumnInfo(name = "allocated_doses") val allocatedDoses: Int,
    @ColumnInfo(name = "dose_amount") val doseAmount: String,
    @ColumnInfo(name = "unit_id") val unitId: Uuid
) {
    /**
     * Выделение в единицах пачки; `null` — доза курса измерена не в [unit]: единицу пачки сменил
     * сосед на сервере, и выделение в старой единице пачку не занимает, пока источник не
     * отключён (PLAN E4).
     */
    fun allocated(vocabulary: Vocabulary, unit: QuantityUnit): Quantity? {
        val dose = storedDose(doseAmount, vocabulary.storedUnit(unitId))
        if (dose.unit != unit) return null
        return dose * Doses(allocatedDoses)
    }
}
