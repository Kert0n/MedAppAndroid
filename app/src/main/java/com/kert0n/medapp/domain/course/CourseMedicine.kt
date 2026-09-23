package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.pack.Availability
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.course.CourseRejected.Companion.rejection
import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.doses
import com.kert0n.medapp.domain.value.Quantity

/**
 * Препарат курса: пачки, которые человек, выбрав их источниками, объявил одним лекарством.
 * Порядок пачек — порядок расходования; каждой выделено целое число доз, потому что доза берётся
 * из одной пачки и между пачками не делится (PLAN D5). Форма и единица препарату не принадлежат:
 * ими лечение задано в назначении, и пачка сверяется с ним, а не с первой пачкой.
 * Взаимозаменяемость приложение не выводит (C2), поэтому вопросы обеспечения задаются препарату
 * целиком. Разовую дозу знает курс и передаёт аргументом, расклад доступного приносит
 * [Availability] — по числу на каждую пачку.
 *
 * Состав держит ссылки на пачки: курс, прочитанный из базы, читает и их, подставить вместо
 * пачки форму или единицу нечем, а списать из пачки через курс — тоже. Публичная сторона у
 * лечения одна, и это курс.
 */
class CourseMedicine(sources: List<CourseSource> = emptyList()) {

    /**
     * Своя копия, а не переданный список: `val` защищает ссылку, а не содержимое, и список,
     * оставшийся у вызывающего, добавил бы пачку в обход проверки уникальности и без роста
     * редакции курса.
     */
    val sources: List<CourseSource> = sources.toList()

    init {
        require(sources.distinctBy { it.pkg }.size == sources.size) {
            "одна пачка входит в курс один раз"
        }
    }

    val isEmpty: Boolean get() = sources.isEmpty()

    val allocatedTotal: Doses
        get() = sources.fold(0.doses) { total, source -> total + source.allocatedDoses }

    internal fun allocatedTo(pkg: PackageRef): Doses? =
        sources.firstOrNull { it.pkg == pkg }?.allocatedDoses

    internal fun holds(pkg: PackageRef): Boolean = sources.any { it.pkg == pkg }

    /**
     * Подключает пачку последней в расходе, если она годится под назначенное: та же форма и та
     * же единица, что у [dose]. Требует живую [Package], а не ссылку: источником бывает только
     * коробка, которая у человека есть, — а есть она или нет, знает лишь тот, кто её прочитал
     * (PLAN D3, D5). Отказ называет причину, ведущую к действию.
     */
    internal fun attach(
        pkg: Package,
        doses: Doses,
        dose: Dose,
        form: DosageForm
    ): Result<CourseMedicine> {
        val ref = pkg.ref
        val rejection = when {
            !pkg.status.allowsUse -> CourseRejected.Reason.PACKAGE_UNUSABLE
            holds(ref) -> CourseRejected.Reason.ALREADY_ATTACHED
            // Пачка без формы не годится ни под какое назначение: сказать, тот ли это препарат,
            // нечем, и сначала форму надо заполнить.
            ref.form == null -> CourseRejected.Reason.FORM_UNKNOWN
            else -> CourseSource.Fault.between(ref, dose, form)?.rejection
        }
        if (rejection != null) return Result.failure(CourseRejected(rejection))
        return Result.success(withSources(sources + CourseSource(ref, doses)))
    }

    /** Убирает пачку; препарат без пачек — законное состояние, курс просто не обеспечен. */
    internal fun detach(pkg: PackageRef): CourseMedicine {
        requireHolds(pkg)
        return withSources(sources.filterNot { it.pkg == pkg })
    }

    /** Переставляет пачку: место в препарате — очередь в расходе. */
    internal fun reorder(from: Int, to: Int): CourseMedicine {
        require(from in sources.indices && to in sources.indices) {
            "источника нет на позиции: $from → $to при ${sources.size} источниках"
        }
        if (from == to) return this
        val moved = sources.toMutableList()
        moved.add(to, moved.removeAt(from))
        return withSources(moved)
    }

    /**
     * Задаёт выделение пачки в целых дозах; верхнюю границу называет [maxDoses]. Отключённому
     * источнику выделить нечего — отказ его причиной, а не падение на правиле «отключённый ничего
     * не держит» (PLAN D5).
     */
    internal fun allocate(pkg: PackageRef, doses: Doses): Result<CourseMedicine> {
        requireHolds(pkg)
        val fault = faultOf(pkg)
        if (fault != null && !doses.isNone) return Result.failure(CourseRejected(fault.rejection))
        return Result.success(withSources(sources.map { if (it.pkg == pkg) it.copy(allocatedDoses = doses) else it }))
    }

    /** Источник, из которого можно принимать: в составе **и** исправен. Отключённый — не источник (PLAN D5). */
    internal fun usable(pkg: PackageRef): Boolean = sources.any { it.pkg == pkg && it.isUsable }

    /**
     * Источник отключается с причиной: выделение — ноль, место в составе остаётся, чтобы человек
     * увидел, что случилось и с какой коробкой (PLAN D5). Уже отключённый по той же причине не
     * меняется.
     */
    internal fun fault(pkg: PackageRef, fault: CourseSource.Fault): CourseMedicine {
        requireHolds(pkg)
        return withSources(sources.map { if (it.pkg == pkg) CourseSource(pkg, 0.doses, fault) else it })
    }

    /** Совместимость вернулась: причина снимается, выделение остаётся нулём — сколько выделить, решает человек. */
    internal fun restore(pkg: PackageRef): CourseMedicine {
        requireHolds(pkg)
        return withSources(sources.map { if (it.pkg == pkg) CourseSource(pkg, 0.doses) else it })
    }

    internal fun faultOf(pkg: PackageRef): CourseSource.Fault? = sources.firstOrNull { it.pkg == pkg }?.fault

    /** Есть ли в составе отключённый источник: с ним лечение не начинается (PLAN D5). */
    val firstFault: CourseSource.Fault? get() = sources.firstNotNullOfOrNull { it.fault }

    /**
     * Обеспечение [required] оставшихся доз, чьи пункты идут в календарном порядке в [remaining].
     * Пачка покрывает не больше выделенного и не больше целых доз, что в ней есть; остаток меньше
     * дозы виден в её строке и в следующую не переливается. Из пунктов читаются только покрытые и
     * первый непокрытый — дальше обеспечению смотреть незачем.
     */
    internal fun coverage(
        dose: Dose,
        required: Doses,
        remaining: Sequence<ScheduledOccurrence>,
        availability: Availability,
        zone: java.time.ZoneId
    ): CourseCoverage {
        val capacities = capacities(dose, availability).associateBy { it.pkg }
        val supplied = capacities.values.fold(0.doses) { total, it -> total + it.covers }
        val covered = minOf(required, supplied)
        val edge = remaining.take(covered.count + 1).toList()
        return CourseCoverage(
            requiredDoses = required,
            coveredDoses = covered,
            coveredUntil = edge.getOrNull(covered.count - 1)?.at,
            firstUncoveredAt = edge.getOrNull(covered.count)?.at,
            zone = zone,
            // Отключённый источник — строкой с причиной: ничего не даёт, но виден (PLAN D5).
            perSource = sources.map { source ->
                val capacity = capacities[source.pkg]
                if (capacity == null) {
                    CourseCoverage.Source(source.pkg, 0.doses, 0.doses, Quantity.zero(dose.unit), 0.doses, source.fault)
                } else {
                    CourseCoverage.Source(source.pkg, capacity.allocated, capacity.covers, capacity.leftover, maxDoses(source.pkg, dose, required, availability))
                }
            }
        )
    }

    /**
     * Верхняя граница выделения пачки [pkg] в целых дозах: меньшее из того, что пачка даёт, и
     * того, что [required] оставляет сверх выделенного остальным. С других пачек выделение само
     * не снимается — это решение человека (C1). [pkg] может ещё не быть в препарате: «сколько
     * выделю, если подключу».
     */
    internal fun maxDoses(
        pkg: PackageRef,
        dose: Dose,
        required: Doses,
        availability: Availability
    ): Doses {
        // Отключённому источнику выделять нечего: он не в той единице, чтобы считать дозы.
        if (faultOf(pkg) != null) return 0.doses
        return minOf(availability.dosesOf(pkg, dose), needLeftFor(pkg, required))
    }

    /**
     * Сколько [required] оставляет пачке [pkg] сверх выделенного остальным. Без коробок: у
     * черновика их никто не читал, и предел ему ставит одна потребность (PLAN D5). [pkg] может
     * ещё не быть в препарате — тогда выделенного у неё нет.
     */
    internal fun needLeftFor(pkg: PackageRef, required: Doses): Doses =
        required.minusOrNone(allocatedTotal - (allocatedTo(pkg) ?: 0.doses))

    /**
     * Выделения, зажатые под нехватку и под потребность: каждой пачке — не больше целых доз, что
     * в ней есть, а избыток сверх [required] снимается с конца, потому что сверху расходуют, а
     * снизу освобождают. Выделение здесь только уменьшается. Доза и расписание курса от этого не
     * меняются (PLAN D5, C1).
     */
    internal fun clampedTo(
        dose: Dose,
        required: Doses,
        availability: Availability
    ): CourseMedicine {
        val capacities = capacities(dose, availability).associateBy { it.pkg }
        // Отключённый источник остаётся как есть: он ничего не держит и не зажимается.
        val clamped = sources.map { source -> capacities[source.pkg]?.let { CourseSource(source.pkg, it.covers) } ?: source }
        return withSources(clamped).withinNeed(required)
    }

    /**
     * Выделения без избытка сверх [required]: лишнее снимается **с конца**, потому что сверху
     * расходуют, а снизу освобождают. Выделение здесь только уменьшается.
     *
     * Правило отдельно от [clampedTo], потому что просят его порознь: у идущего лечения выделения
     * зажимает ещё и нехватка в коробках, а у черновика коробок никто не читал — ему нужно ровно
     * «не больше, чем приёмов» (PLAN D5).
     */
    internal fun withinNeed(required: Doses): CourseMedicine {
        var excess = allocatedTotal.minusOrNone(required)
        if (excess.isNone) return this
        val trimmed = sources.toMutableList()
        for (index in trimmed.indices.reversed()) {
            if (excess.isNone) break
            val source = trimmed[index]
            val taken = minOf(source.allocatedDoses, excess)
            trimmed[index] = source.copy(allocatedDoses = source.allocatedDoses - taken)
            excess -= taken
        }
        return withSources(trimmed)
    }

    /**
     * Из каких пачек и по сколько уйдут следующие [doses] доз: сверху вниз, каждая пачка — не
     * больше выделенного и не больше целых доз, что в ней есть. Пачек, из которых не уходит
     * ничего, в ответе нет; порядок ответа — порядок расходования.
     */
    internal fun spend(
        dose: Dose,
        doses: Doses,
        availability: Availability
    ): Map<PackageRef, Doses> {
        var left = doses
        val spent = LinkedHashMap<PackageRef, Doses>()
        for (capacity in capacities(dose, availability)) {
            if (left.isNone) break
            val taken = minOf(capacity.covers, left)
            if (taken.isNone) continue
            spent[capacity.pkg] = taken
            left -= taken
        }
        return spent
    }

    /**
     * Выделения после того, как названные дозы ушли: из каждой пачки — на столько, сколько из неё
     * взято, и не ниже нуля. Так уходит доза, принятая мимо плана: бронь уменьшается по порядку
     * расходования, а остатка пачки это не касается.
     */
    internal fun spent(spent: Map<PackageRef, Doses>): CourseMedicine = withSources(
        sources.map { source ->
            val taken = spent[source.pkg] ?: return@map source
            source.copy(allocatedDoses = source.allocatedDoses.minusOrNone(taken))
        }
    )

    /**
     * Сколько целых доз остаётся выделено пачке после приёма [taken]: не больше выделенного за
     * вычетом расхода и не больше [availableAfter] (PLAN D5). Нулевое выделение расходом не
     * оживает: приём из невыделенной пачки брони не создаёт.
     */
    internal fun dosesAfterIntake(
        pkg: PackageRef,
        dose: Dose,
        taken: Dose,
        availableAfter: Quantity
    ): Doses {
        require(availableAfter.unit == dose.unit) {
            "доступный остаток измеряется единицей дозы: ${availableAfter.unit} и ${dose.unit}"
        }
        val allocated = allocatedTo(pkg) ?: 0.doses
        if (allocated.isNone) return 0.doses
        val leftAllocated = (dose * allocated).minusOrZero(taken.quantity)
        val limited =
            if (leftAllocated.amount <= availableAfter.amount) leftAllocated else availableAfter
        return limited.dosesIn(dose)
    }

    /**
     * Что даёт каждая пачка под дозу, в порядке расходования. Одно место на все вопросы: пока
     * обеспечение, расход и зажим считали это порознь, правило «не больше выделенного и не больше
     * целых доз, что в пачке есть» было написано трижды и могло разойтись.
     */
    private fun capacities(dose: Dose, availability: Availability): List<SourceCapacity> =
        sources.filter { it.isUsable }.map { source ->
            val available = availability.of(source.pkg)
            val whole = available.dosesIn(dose)
            SourceCapacity(
                pkg = source.pkg,
                allocated = source.allocatedDoses,
                whole = whole,
                leftover = available - dose * whole
            )
        }

    /**
     * Пачка под дозой: сколько целых доз в ней есть ([whole]) и сколько из них покрывает приёмы
     * ([covers]) — не больше выделенного.
     */
    private data class SourceCapacity(
        val pkg: PackageRef,
        val allocated: Doses,
        val whole: Doses,
        val leftover: Quantity
    ) {
        val covers: Doses get() = minOf(allocated, whole)
    }

    /** Тот же состав без выделений: точка отсчёта для разницы броней — до начала и после конца (PLAN D5). */
    internal fun unallocated(): CourseMedicine = withSources(sources.map { it.copy(allocatedDoses = 0.doses) })

    private fun withSources(sources: List<CourseSource>): CourseMedicine = CourseMedicine(sources)

    override fun equals(other: Any?): Boolean =
        this === other || (other is CourseMedicine && sources == other.sources)

    override fun hashCode(): Int = sources.hashCode()

    override fun toString(): String = "CourseMedicine($sources)"

    private fun requireHolds(pkg: PackageRef) {
        require(holds(pkg)) { "пачка ${pkg.id} не источник этого курса" }
    }
}
