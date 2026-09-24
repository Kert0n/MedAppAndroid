package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.DosageForm

/**
 * Годится ли коробка в источник лечения — один ответ и для подключения, и для выбора на экране
 * (PLAN D5). Порядок вопросов — порядок отказов: непригодную не берут вовсе, уже взятую второй
 * раз не берут, без формы не с чем сверять, дальше решают форма и единица.
 */
sealed interface SourceFit {

    data object Fits : SourceFit

    /** Коробкой уже не пользуются: её выбрасывают или её полки больше нет. */
    data object Unusable : SourceFit

    /** Уже в составе этого лечения. */
    data object Attached : SourceFit

    /** У коробки не заполнена форма: сказать, тот ли это препарат, нечем. */
    data object NeedsForm : SourceFit

    /** Форма или единица коробки не те, что назначены. */
    data class Mismatch(val fault: CourseSource.Fault) : SourceFit

    companion object {

        fun of(pkg: PackageRef, usable: Boolean, attached: Boolean, dose: Dose, form: DosageForm): SourceFit = when {
            !usable -> Unusable
            attached -> Attached
            pkg.form == null -> NeedsForm
            else -> CourseSource.Fault.between(pkg, dose, form)?.let(::Mismatch) ?: Fits
        }
    }
}
