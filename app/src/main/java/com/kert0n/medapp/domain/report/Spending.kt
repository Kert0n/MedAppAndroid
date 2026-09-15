package com.kert0n.medapp.domain.report

import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.domain.intake.UnplannedIntake
import com.kert0n.medapp.domain.value.Quantity
import kotlin.uuid.Uuid

/**
 * Сколько я истратил: сумма моих состоявшихся приёмов, и ничего больше (PLAN H6). Пропуск, отказ,
 * доза мимо плана, пересчёт, утилизация, перенос и чужой расход — не мои приёмы, и сюда не
 * попадают; отвергнутый сервером расход — выпитая таблетка, и попадает.
 *
 * Строка — то, что человек лечил: курсовой приём идёт в строку своего эпизода, разовый — в строку
 * своей коробки. Одинаковые названия строк не объединяют (C0), а разные единицы не складываются:
 * эпизод, чью единицу сменили, даёт строку на каждую. Денег здесь нет — только количества.
 */
data class Spending(
    val episodes: List<Episode>,
    val packages: List<Box>
) {

    /** Истраченное по эпизоду лечения в одной единице: [intakes] приёмов на [total]. */
    data class Episode(val record: CourseRecordProjection, val total: Quantity, val intakes: Int)

    /**
     * Истраченное разовыми приёмами из одной коробки **в одной единице**: коробку могли
     * переописать, и приёмы помнят каждый свою — складывать можно только внутри единицы, поэтому
     * строка — по предмету и единице, как у эпизода (C1). Коробка названа своими величинами, а не
     * ссылкой: ссылка сравнивается по `id`, и переименованная коробка не отличалась бы в строке.
     */
    data class Box(val packageId: Uuid, val name: String, val total: Quantity, val intakes: Int)

    val isEmpty: Boolean get() = episodes.isEmpty() && packages.isEmpty()

    companion object {

        /**
         * Отчёт из состоявшихся приёмов [taken] и записей эпизодов [records], к которым относятся
         * курсовые. Строка — по предмету **и единице**, у эпизодов и коробок одинаково. Эпизоды —
         * от начатых позже, коробки — по названию.
         */
        fun of(taken: List<Intake>, records: Map<Uuid, CourseRecordProjection>): Spending {
            val facts = taken.mapNotNull { intake -> intake.taken?.let { intake to it } }
            val episodes = facts.filter { (intake, _) -> intake is CourseIntake }
                .groupBy { (intake, dose) -> (intake as CourseIntake).courseId to dose.amount.unit }
                .map { (key, group) ->
                    val record = requireNotNull(records[key.first]) { "курсовой приём держится за запись эпизода ${key.first}" }
                    Episode(record, group.map { it.second.amount.quantity }.reduce(Quantity::plus), group.size)
                }
                .sortedWith(compareByDescending<Episode> { it.record.startedAt }.thenBy { it.total.unit.name })
            val packages = facts.filter { (intake, _) -> intake is UnplannedIntake }
                .groupBy { (_, dose) -> dose.pkg.id to dose.amount.unit }
                .map { (key, group) ->
                    val latest = group.maxBy { it.second.at }.second.pkg
                    Box(key.first, latest.name, group.map { it.second.amount.quantity }.reduce(Quantity::plus), group.size)
                }
                .sortedWith(compareBy<Box> { it.name }.thenBy { it.packageId.toString() }.thenBy { it.total.unit.name })
            return Spending(episodes, packages)
        }

        val EMPTY = Spending(emptyList(), emptyList())
    }
}
