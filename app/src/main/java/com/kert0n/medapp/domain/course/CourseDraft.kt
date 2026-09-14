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
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.requireOptionalText
import com.kert0n.medapp.domain.value.requireText
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Черновик курса — законное сохраняемое состояние: названия уже достаточно (PLAN D5). Назначение
 * собирается по частям из словаря — доза с единицей, форма, календарь, число доз — и пачки для
 * этого не нужно: человек записывает у врача, а покупает потом. Пачка подключается к тому, что
 * назначено, и годится ли она, решают уже названные доза и форма. Броней у черновика нет:
 * выбранные пачки — предварительный выбор.
 */
class CourseDraft(
    val id: Uuid,
    val title: String,
    val note: String? = null,
    val dose: Dose? = null,
    val form: DosageForm? = null,
    val schedule: CourseSchedule? = null,
    val totalDoses: Doses? = null,
    val medicine: CourseMedicine = CourseMedicine(),
    val revision: Revision = Revision.initial,
    val createdAt: Instant,
    val updatedAt: Instant
) {

    init {
        requireText(title, CourseRecord.TITLE_MAX_LENGTH, "CourseDraft.title")
        requireOptionalText(note, CourseRecord.NOTE_MAX_LENGTH, "CourseDraft.note")
        // Пачка проходит в препарат только через сверку с дозой и формой, поэтому препарат без
        // них — состояние, которого не бывает.
        require(medicine.isEmpty || (dose != null && form != null)) {
            "пачки подключаются к назначенным дозе и форме"
        }
    }

    val sources: List<CourseSource> get() = medicine.sources

    val unit: QuantityUnit? get() = dose?.unit

    val allocatedDosesTotal: Doses get() = medicine.allocatedTotal

    /** Как черновик видит экран: величина, наружу уходит она, а не сущность. */
    fun projection(): CourseDraftProjection = CourseDraftProjection(
        id = id,
        title = title,
        note = note,
        dose = dose,
        form = form,
        schedule = schedule,
        totalDoses = totalDoses,
        sources = sources,
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /** Выделение пачки в единицах пачки; `null` — пачка не выбрана. */
    fun allocatedOf(pkg: PackageRef): Quantity? {
        val allocated = medicine.allocatedTo(pkg) ?: return null
        return dose?.times(allocated)
    }

    /**
     * Название и заметка правятся без роста редакции: редакция отмечает изменение будущих пунктов,
     * а исправленная опечатка их не меняет. Название препарата — тоже сюда, свободным текстом.
     */
    fun rename(title: String, note: String?, at: Instant): CourseDraft =
        changed(title = title, note = note, updatedAt = at)

    /**
     * Доза черновика; у начатого лечения её меняет `Course.changeDose` (PLAN D5). Единица дозы —
     * единица лечения; уже
     * подключённые пачки другой единицы под неё не годятся, и такая смена отвергается.
     */
    fun setDose(dose: Dose, at: Instant): Result<CourseDraft> {
        if (!medicine.isEmpty && dose.unit != this.dose?.unit) {
            return Result.failure(CourseRejected(CourseRejected.Reason.UNIT_MISMATCH))
        }
        return Result.success(changed(dose = dose, revision = revision.next(), updatedAt = at))
    }

    /** Форма лечения — тоже только у черновика, и уже подключённые пачки ей обязаны. */
    fun setForm(form: DosageForm, at: Instant): Result<CourseDraft> {
        if (!medicine.isEmpty && form != this.form) {
            return Result.failure(CourseRejected(CourseRejected.Reason.FORM_MISMATCH))
        }
        return Result.success(changed(form = form, revision = revision.next(), updatedAt = at))
    }

    /**
     * Расписание черновика; у начатого лечения его меняет `Course.changeSchedule`. Редакция растёт,
     * потому что меняется состав будущих пунктов.
     */
    fun setSchedule(schedule: CourseSchedule, at: Instant): CourseDraft =
        changed(schedule = schedule, revision = revision.next(), updatedAt = at)

    /**
     * Сколько всего доз назначено. Число доз правится и после начала — но уже у курса. Ноль здесь
     * не отвергается: правило «лечение без единой дозы — не лечение» живёт на [Prescription], и
     * такой черновик просто не активируется.
     */
    fun setTotalDoses(totalDoses: Doses, at: Instant): CourseDraft =
        changed(totalDoses = totalDoses, revision = revision.next(), updatedAt = at)

    /**
     * Подключает пачку к препарату последней в очереди расходования. Сверяет её назначение, а не
     * первая пачка: пока доза и форма не названы, сверять не с чем, и отказ говорит, чего не
     * хватает.
     */
    fun attach(pkg: Package, doses: Doses, at: Instant): Result<CourseDraft> {
        val dose = dose ?: return rejected(CourseRejected.Reason.DOSE_MISSING)
        val form = form ?: return rejected(CourseRejected.Reason.FORM_MISSING)
        return medicine.attach(pkg, doses, dose, form)
            .map { changed(medicine = it, revision = revision.next(), updatedAt = at) }
    }

    /** Годится ли пачка под то, что уже названо; пока доза или форма не названы — сверять не с чем. */
    fun faultOf(pkg: PackageRef): CourseSource.Fault? {
        val dose = dose ?: return null
        val form = form ?: return null
        return CourseSource.Fault.between(pkg, dose, form)
    }

    /** Сосед сменил у коробки единицу или форму: источник отключён с причиной (PLAN D5). */
    fun faultSource(pkg: PackageRef, fault: CourseSource.Fault, at: Instant): CourseDraft {
        if (medicine.faultOf(pkg) == fault) return this
        return changed(medicine = medicine.fault(pkg, fault), revision = revision.next(), updatedAt = at)
    }

    /** Совместимость вернулась: причина снимается, выделение — ноль. */
    fun restoreSource(pkg: PackageRef, at: Instant): CourseDraft {
        if (medicine.faultOf(pkg) == null) return this
        return changed(medicine = medicine.restore(pkg), revision = revision.next(), updatedAt = at)
    }

    /** Отвязка пачки назначения не касается: доза и форма заданы словарём, а не пачкой. */
    fun detach(pkg: PackageRef, at: Instant): CourseDraft = changed(
        medicine = medicine.detach(pkg),
        revision = revision.next(),
        updatedAt = at
    )

    fun reorder(from: Int, to: Int, at: Instant): CourseDraft {
        val moved = medicine.reorder(from, to)
        if (moved == medicine) return this
        return changed(medicine = moved, revision = revision.next(), updatedAt = at)
    }

    /** Выделение пачке; отключённому источнику — отказ его причиной (PLAN D5). */
    fun allocate(pkg: PackageRef, doses: Doses, at: Instant): Result<CourseDraft> =
        medicine.allocate(pkg, doses).map { changed(medicine = it, revision = revision.next(), updatedAt = at) }

    /**
     * Верхняя граница ползунка пачки. Пока доза не задана, границы нет: выделять нечего, и ноль
     * здесь честнее выдуманного числа.
     */
    fun maxDoses(pkg: PackageRef, required: Doses, availability: Availability): Doses {
        val dose = dose ?: return 0.doses
        return medicine.maxDoses(pkg, dose, required, availability)
    }

    /**
     * Активация: нужны расписание, доза, форма и число доз — и ничего сверх: лечение начинается
     * и без лекарства на руках, необеспеченным, а пачка подключается, когда её купят. Дальше
     * наличие назначения обеспечивает тип [Course], а выделения становятся бронями (PLAN D5, F1).
     *
     * Рождаются **двое**: план, которым пользуются, и запись, которая останется, когда план
     * уничтожится. Возвращаются они вместе, поэтому завести эпизод без записи невозможно — а
     * значит, аналитике не придётся собирать историю из идущих курсов и закрытых по отдельности.
     */
    fun activate(at: Instant): Result<Activation> {
        val schedule = schedule ?: return rejected(CourseRejected.Reason.SCHEDULE_MISSING)
        val dose = dose ?: return rejected(CourseRejected.Reason.DOSE_MISSING)
        val form = form ?: return rejected(CourseRejected.Reason.FORM_MISSING)
        val totalDoses = totalDoses?.takeUnless { it.isNone }
            ?: return rejected(CourseRejected.Reason.TOTAL_DOSES_MISSING)
        // С отключённым источником лечение не начинается: человек его убирает или чинит (PLAN D5).
        medicine.firstFault?.let { return rejected(it.rejection) }
        val prescription = Prescription(
            dose = dose,
            form = form,
            schedule = schedule,
            totalDoses = totalDoses
        )
        return Result.success(
            Activation(
                course = Course(
                    id = id,
                    prescription = prescription,
                    medicine = medicine,
                    revision = revision,
                    createdAt = createdAt,
                    updatedAt = at
                ),
                record = CourseRecord(
                    id = id,
                    title = title,
                    note = note,
                    prescription = prescription,
                    startedAt = at
                )
            )
        )
    }

    private fun <T> rejected(reason: CourseRejected.Reason): Result<T> =
        Result.failure(CourseRejected(reason))

    /** Изменённый экземпляр; [id] и [createdAt] не меняются. */
    private fun changed(
        title: String = this.title,
        note: String? = this.note,
        dose: Dose? = this.dose,
        form: DosageForm? = this.form,
        schedule: CourseSchedule? = this.schedule,
        totalDoses: Doses? = this.totalDoses,
        medicine: CourseMedicine = this.medicine,
        revision: Revision = this.revision,
        updatedAt: Instant = this.updatedAt
    ): CourseDraft = CourseDraft(
        id = id,
        title = title,
        note = note,
        dose = dose,
        form = form,
        schedule = schedule,
        totalDoses = totalDoses,
        medicine = medicine,
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /** Тождество — [id]: переименованный черновик остаётся тем же черновиком. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is CourseDraft && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "CourseDraft(id=$id, title=$title)"

    /**
     * Начатое лечение: план и запись одного эпизода, с общим [Course.id] и одним назначением.
     * Один тип на двоих потому, что порознь они не рождаются.
     */
    class Activation(val course: Course, val record: CourseRecord)
}
