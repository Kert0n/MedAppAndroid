package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.CourseMedicine
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.pack.Availability
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Quantity
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Что получится у состава, который человек собрал на экране: предел каждой строки и итог — сколько
 * приёмов обеспечено и скольких не хватает.
 *
 * Это **вопрос, а не действие**: ни транзакции, ни записи, ни исхода у него нет, и отвечает он
 * мгновенно, сколько бы раз экран ни спросил. Считает всё домен — предел `CourseMedicine.maxDoses`,
 * итог `clampedTo` (каждая коробка даёт не больше, чем в ней есть, а лишнее сверх потребности
 * снимается с конца, PLAN D5). Здесь — дверь к нему для представления: экран спрашивает `feature`,
 * а не заглядывает внутрь препарата курса (PLAN H1).
 *
 * Состав приходит тот, что на экране: и предел, и итог зависят от выделенного соседям, а считать
 * их по записанному значило бы показывать вчерашние числа (H3 №16). Коробки, которой в раскладе
 * нет, в лечении уже не будет — она не даёт ничего, и это то же правило, по которому собирает
 * расклад хранение (D5).
 */
class SourceEstimates @Inject constructor() {

    fun of(
        sources: List<CourseSource>,
        dose: Dose,
        required: Doses,
        availableToMe: Map<Uuid, Quantity>
    ): Estimate {
        val availability = Availability(
            sources.associate { source ->
                source.pkg.id to (availableToMe[source.pkg.id] ?: Quantity.zero(dose.unit))
            }
        )
        val medicine = CourseMedicine(sources)
        val covered = medicine.clampedTo(dose, required, availability).allocatedTotal
        return Estimate(
            limits = sources.associate { source ->
                source.pkg.id to medicine.maxDoses(source.pkg, dose, required, availability)
            },
            requiredDoses = required,
            coveredDoses = covered,
            missingDoses = required.minusOrNone(covered)
        )
    }

    /**
     * Оценка состава: [limits] — предел ползунка каждой коробки, [coveredDoses] — сколько приёмов
     * этот состав покрывает, [missingDoses] — скольких не хватает до [requiredDoses].
     */
    data class Estimate(
        val limits: Map<Uuid, Doses>,
        val requiredDoses: Doses,
        val coveredDoses: Doses,
        val missingDoses: Doses
    )
}
