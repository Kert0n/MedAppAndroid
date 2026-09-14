package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.pack.Availability
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.doses
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Курс — лечение, которым пользуются прямо сейчас: назначение и пачки, из которых оно берётся.
 * Сущность, тождество — [id] эпизода, общее с его записью. Состояний нет: живой план всегда
 * действующий, а закончившееся лечение планом быть перестаёт — план уничтожается, остаётся
 * [CourseRecord] (PLAN D5). Имя лечения живёт там же, а не здесь: так называют лечение, а не
 * расписание.
 *
 * Курс владеет тем, сколько осталось: назначенное число доз за вычетом принятых по плану и
 * [takenOffPlan] — принятых мимо него. Пропущенная доза никуда не исчезает — лечение
 * растягивается, а ожидаемый конец сдвигается сам; закончить раньше человек может, сократив
 * число доз рукой. Все вопросы о лечении задаются курсу — обеспечение, предел ползунка, зажим
 * при нехватке, пересчёт после приёма, порядок расхода, — потому что он один владеет и дозой, и
 * препаратом. Пачки — ссылками [PackageRef]: подставить вместо пачки форму или единицу нечем,
 * а списать из пачки через курс — тоже.
 */
class Course(
    val id: Uuid,
    val prescription: Prescription,
    val medicine: CourseMedicine,
    val takenOffPlan: Doses = 0.doses,
    val revision: Revision = Revision.initial,
    val createdAt: Instant,
    val updatedAt: Instant
) {

    /** Разовая доза: назначение врача, а не подсказка упаковки (PLAN D5, C1). */
    val dose: Dose get() = prescription.dose

    val schedule: CourseSchedule get() = prescription.schedule

    val sources: List<CourseSource> get() = medicine.sources

    /** Форма и единица — назначения, а не первой пачки: пачки приходят и уходят, они остаются. */
    val form: DosageForm get() = prescription.form

    val unit: QuantityUnit get() = prescription.dose.unit

    val totalDoses: Doses get() = prescription.totalDoses

    /** Как курс видит экран: величина, наружу уходит она, а не сущность. */
    fun projection(): CourseProjection = CourseProjection(
        id = id,
        prescription = prescription,
        sources = sources,
        takenOffPlan = takenOffPlan,
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /**
     * Сколько доз ещё впереди при [progress] по плану. Что принято, знают приёмы — курс их не
     * хранит и получает прогресс аргументом; принятое мимо плана он знает сам. Больше
     * назначенного не бывает: лишнее — ноль.
     */
    fun remainingDoses(progress: CourseProgress): Doses =
        totalDoses.minusOrNone(progress.takenDoses + takenOffPlan)

    /**
     * Дозы, принятые мимо плана: таблетки ушли, а курс об этом не узнал — взяли одиночным
     * приёмом, из чужой аптечки, из кармана. Это не приём, а поправка к счёту курса: расход уже
     * учтён там, где произошёл, и второй раз не считается — остатка пачек она не касается и в
     * аналитику не входит. Потребность уменьшается, а с ней и бронь — по порядку расходования,
     * как ушла бы плановая доза; редакция растёт, потому что бронь изменилась. Уменьшение числа
     * бронь обратно не растит: снимать её решал человек, и возвращать её догадкой нельзя.
     */
    fun setTakenOffPlan(total: Doses, availability: Availability, at: Instant): Course {
        if (total == takenOffPlan) return this
        val added = total.minusOrNone(takenOffPlan)
        return changed(
            medicine = medicine.spent(medicine.spend(dose, added, availability)),
            takenOffPlan = total,
            revision = revision.next(),
            updatedAt = at
        )
    }

    /**
     * Когда наступят оставшиеся дозы: столько ближайших **неотвеченных** пунктов календаря с
     * начала курса, сколько доз осталось. Момента «с какого читать» нет: неотвеченный утренний
     * приём в полдень никуда не делся, а отвеченные пункты — принятые и пропущенные — календарь
     * минует по [progress]. Отсюда и конец лечения: пропуск сдвигает его вперёд, поздний ответ по
     * пропущенному — назад, и лишний материализованный пункт тогда убирает сценарий.
     */
    fun remainingOccurrences(progress: CourseProgress): List<ScheduledOccurrence> =
        schedule.next(schedule.beginning, remainingDoses(progress).count, progress.answered)

    /**
     * Сколько доз придётся на [from, until), если все приёмы состоятся (ТЗ 4.1.1.10.1): оставшиеся
     * дозы раскладываются по неотвеченным пунктам начиная с [from], и считаются те, что легли до
     * [until]. Неотвеченный пункт раньше [from] в счёт не идёт: его место пропущено, и доза уехала
     * вперёд, как уезжает у неответа (PLAN D6).
     */
    fun dosesDue(progress: CourseProgress, from: Instant, until: Instant): Doses =
        Doses(schedule.next(from, remainingDoses(progress).count, progress.answered).count { it.at.isBefore(until) })

    /** Ожидаемый конец — последняя из оставшихся доз; `null` — принято всё. */
    fun expectedEnd(progress: CourseProgress): ScheduledOccurrence? =
        remainingOccurrences(progress).lastOrNull()

    /**
     * Число доз правится и после начала: пропуски растянули лечение, или врач сократил его.
     * Редакция растёт — меняется состав будущих пунктов; снимок назначения в записи эпизода
     * переписывает та же транзакция (PLAN F5). Ноль доз — не лечение: отказ, а не исключение.
     */
    fun setTotalDoses(totalDoses: Doses, at: Instant): Result<Course> {
        if (totalDoses.isNone) return rejected(CourseRejected.Reason.TOTAL_DOSES_MISSING)
        if (totalDoses == this.totalDoses) return Result.success(this)
        return Result.success(
            changed(prescription = prescription.withTotalDoses(totalDoses), revision = revision.next(), updatedAt = at)
        )
    }

    /**
     * Врач сменил дозу — это то же лечение (PLAN C1, D5): отвеченные пункты помнят прежнюю дозу, а
     * будущие перестраиваются. Единица дозы — единица лечения, и подключённые пачки другой единицы
     * под неё не годятся: сначала отвязать.
     */
    fun changeDose(dose: Dose, at: Instant): Result<Course> {
        if (dose == this.dose) return Result.success(this)
        if (!medicine.isEmpty && dose.unit != unit) return rejected(CourseRejected.Reason.UNIT_MISMATCH)
        return Result.success(changed(prescription = prescription.copy(dose = dose), revision = revision.next(), updatedAt = at))
    }

    /** Другая форма под подключёнными пачками — другой препарат: сначала отвязать (PLAN D5). */
    fun changeForm(form: DosageForm, at: Instant): Result<Course> {
        if (form == this.form) return Result.success(this)
        if (!medicine.isEmpty) return rejected(CourseRejected.Reason.FORM_MISMATCH)
        return Result.success(changed(prescription = prescription.copy(form = form), revision = revision.next(), updatedAt = at))
    }

    /**
     * Другое расписание — в том числе другая зона — то же лечение, и будущие пункты перестраиваются
     * по нему. Прошлое уже случилось: новое расписание не начинается раньше сегодняшнего дня своей
     * зоны, иначе в нём завелись бы пункты, на которые отвечать поздно (PLAN D5).
     */
    fun changeSchedule(schedule: CourseSchedule, at: Instant): Result<Course> {
        if (schedule == this.schedule) return Result.success(this)
        if (schedule.start.isBefore(at.atZone(schedule.zone).toLocalDate())) {
            return rejected(CourseRejected.Reason.SCHEDULE_IN_PAST)
        }
        return Result.success(changed(prescription = prescription.copy(schedule = schedule), revision = revision.next(), updatedAt = at))
    }

    private fun <T> rejected(reason: CourseRejected.Reason): Result<T> = Result.failure(CourseRejected(reason))

    val allocatedDosesTotal: Doses get() = medicine.allocatedTotal

    /**
     * Выделение пачки **в единицах пачки** — та самая величина, которую видит серверная бронь:
     * целевой объём равен `allocatedDoses × dose` (PLAN D5). `null` — пачка не в препарате курса.
     */
    fun allocatedOf(pkg: PackageRef): Quantity? =
        medicine.allocatedTo(pkg)?.let { dose * it }

    /**
     * Источник ли эта пачка: из пачки курса принимают его пункт, из любой другой — внеплановый
     * факт, и пункт им не закрывается (PLAN D5). Правило о составе препарата живёт на курсе,
     * а не у того, кто записывает приём.
     */
    fun isSource(pkg: PackageRef): Boolean = medicine.usable(pkg)

    /**
     * Пачки действующего курса менять можно: это не изменение дозы или календаря (PLAN D5).
     * Годится ли пачка, решает назначение: та же форма, та же единица. Подключается живая
     * коробка — та, что у человека на руках в этой транзакции.
     */
    fun attach(pkg: Package, doses: Doses, at: Instant): Result<Course> =
        medicine.attach(pkg, doses, dose, form)
            .map { changed(medicine = it, revision = revision.next(), updatedAt = at) }

    /**
     * Сосед сменил у коробки единицу или форму: источник отключается с причиной, бронь и
     * выделение — ноль, доза и форма лечения прежние (PLAN D5). Уже отключённый по той же
     * причине — тот же курс, без роста редакции.
     */
    fun faultSource(pkg: PackageRef, fault: CourseSource.Fault, at: Instant): Course {
        if (medicine.faultOf(pkg) == fault) return this
        return changed(medicine = medicine.fault(pkg, fault), revision = revision.next(), updatedAt = at)
    }

    /** Совместимость вернулась: причина снимается, выделение — ноль; исправный источник не трогается. */
    fun restoreSource(pkg: PackageRef, at: Instant): Course {
        if (medicine.faultOf(pkg) == null) return this
        return changed(medicine = medicine.restore(pkg), revision = revision.next(), updatedAt = at)
    }

    /** Отвязка последней пачки лечения не отменяет: курс просто становится необеспеченным. */
    fun detach(pkg: PackageRef, at: Instant): Course = changed(
        medicine = medicine.detach(pkg),
        revision = revision.next(),
        updatedAt = at
    )

    fun reorder(from: Int, to: Int, at: Instant): Course {
        val moved = medicine.reorder(from, to)
        if (moved == medicine) return this
        return changed(medicine = moved, revision = revision.next(), updatedAt = at)
    }

    /** Выделение пачке; отключённому источнику — отказ его причиной (PLAN D5). */
    fun allocate(pkg: PackageRef, doses: Doses, at: Instant): Result<Course> =
        medicine.allocate(pkg, doses).map { changed(medicine = it, revision = revision.next(), updatedAt = at) }

    /**
     * Обеспечение курса: на сколько из оставшихся доз хватит пачек препарата и с какого приёма не
     * хватает (PLAN D5). Потребность — от назначенного числа доз, а не от окна календаря.
     */
    fun coverage(progress: CourseProgress, availability: Availability): CourseCoverage =
        medicine.coverage(dose, remainingOccurrences(progress), availability, schedule.zone)

    /**
     * Верхняя граница ползунка пачки в целых дозах: меньшее из того, что пачка даёт, и того, что
     * потребность оставляет сверх выделенного остальным (PLAN D5).
     */
    fun maxDoses(pkg: PackageRef, required: Doses, availability: Availability): Doses =
        medicine.maxDoses(pkg, dose, required, availability)

    /**
     * Курс с выделениями, зажатыми под нехватку и оставшуюся потребность. Доза, расписание и даты
     * не меняются — расписание это намерение человека, и чужое действие его не переписывает (C1).
     *
     * Зажимать нечего — возвращает себя: пересчёт идёт после каждого изменения входов (D5), и
     * поднимать редакцию на каждом было бы шумом в истории пунктов.
     */
    fun clamped(required: Doses, availability: Availability, at: Instant): Course {
        val clamped = medicine.clampedTo(dose, required, availability)
        if (clamped == medicine) return this
        return changed(medicine = clamped, revision = revision.next(), updatedAt = at)
    }

    /**
     * Сколько целых доз остаётся выделено пачке после подтверждённого приёма: не больше
     * выделенного за вычетом расхода и не больше того, что в пачке осталось (PLAN D5).
     */
    fun dosesAfterIntake(pkg: PackageRef, taken: Dose, availableAfter: Quantity): Doses =
        medicine.dosesAfterIntake(pkg, dose, taken, availableAfter)

    /**
     * Из каких пачек уйдут следующие [doses] доз — по одной пачке на дозу, в порядке расходования:
     * сверху вниз, каждая пачка не больше выделенного и не больше целых доз, что в ней есть.
     * `null` — доза не обеспечена: полная доза «неизвестно откуда» не записывается, и пачки вне
     * препарата не подставляются (PLAN D5).
     *
     * Раскладку по конкретным приёмам делает сценарий: какие пункты ещё не отвечены и в каком они
     * порядке — его знание, а курс отвечает, из чего они возьмутся. Спрашивать у курса список
     * приёмов значило бы тянуть в него чужой агрегат ради двух проверок.
     */
    fun spendOrder(doses: Doses, availability: Availability): List<PackageRef?> {
        val fromPacks = medicine.spend(dose, doses, availability)
            .flatMap { (pkg, taken) -> List(taken.count) { pkg } }
        return List(doses.count) { fromPacks.getOrNull(it) }
    }

    /**
     * Сколько уйдёт из каждой пачки на следующие [doses] доз. Пачек, из которых не уходит ничего,
     * в ответе нет; это тот же расход, что и [spendOrder], только величинами.
     */
    fun spending(doses: Doses, availability: Availability): Map<PackageRef, Quantity> =
        medicine.spend(dose, doses, availability).mapValues { (_, taken) -> dose * taken }

    private fun changed(
        prescription: Prescription = this.prescription,
        medicine: CourseMedicine = this.medicine,
        takenOffPlan: Doses = this.takenOffPlan,
        revision: Revision = this.revision,
        updatedAt: Instant = this.updatedAt
    ): Course = Course(
        id = id,
        prescription = prescription,
        medicine = medicine,
        takenOffPlan = takenOffPlan,
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /** Тождество — [id]: курс с переставленными пачками остаётся тем же курсом. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Course && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Course(id=$id, dose=$dose)"
}
