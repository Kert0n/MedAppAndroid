package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses

/**
 * Пачка препарата курса и сколько целых доз из неё ещё выделено — оставшееся выделение, а не
 * первоначальное: подтверждённая доза уменьшает его на одну. Величина: тождество даёт пара «курс и
 * пачка», а место в препарате — очередь расходования (PLAN D5). Пачка — ссылкой: курс, читая
 * себя, читает и её, но списать из неё через курс нечем.
 *
 * [fault] — источник отключён с причиной: сосед сменил у коробки единицу или форму, и под
 * назначение она больше не годится. Отключённый источник остаётся в составе, чтобы человек
 * увидел, что случилось, но ничего не держит: выделение — ноль, в обеспечении и расходе не
 * участвует (PLAN D5).
 */
data class CourseSource(
    val pkg: PackageRef,
    val allocatedDoses: Doses,
    val fault: Fault? = null
) {
    init {
        require(fault == null || allocatedDoses.isNone) { "отключённый источник ничего не держит: $fault и $allocatedDoses" }
    }

    val isUsable: Boolean get() = fault == null

    /** Почему источник не годится под назначение. Правило одно — на подключение и на проверку. */
    enum class Fault {
        UNIT_MISMATCH,
        FORM_MISMATCH;

        companion object {
            /** Годится ли пачка под дозу и форму: без формы пачка не годится ни под что (PLAN D5). */
            fun between(pkg: PackageRef, dose: Dose, form: DosageForm): Fault? = when {
                pkg.form == null || pkg.form != form -> FORM_MISMATCH
                pkg.unit != dose.unit -> UNIT_MISMATCH
                else -> null
            }
        }
    }
}
