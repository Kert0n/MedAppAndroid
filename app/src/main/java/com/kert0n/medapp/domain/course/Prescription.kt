package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.pack.PackageRef

/**
 * Назначение: чем лечатся и сколько — доза с единицей, форма, календарь и число доз. Собирается
 * из словаря, пачки для этого не нужно: лечение существует с момента, как врач его назвал, а
 * пачка потом подключается к тому, что назначено, и назначение решает, годится ли она (PLAN D5).
 *
 * Изменившееся лечение — тот же эпизод с другим назначением: врач сменил дозу, расписание или форму,
 * пропуски растянули лечение (PLAN C1, D5). Величина: другое назначение — другое значение, и лежит
 * оно в двух местах сразу — у живого плана и у записи эпизода; согласованность при сохранении
 * обеспечивает транзакция (PLAN F5). Что было назначено на конкретный приём, помнит сам приём.
 */
data class Prescription(
    val dose: Dose,
    val form: DosageForm,
    val schedule: CourseSchedule,
    val totalDoses: Doses
) {
    init {
        require(!totalDoses.isNone) { "лечение без единой дозы — не лечение" }
    }

    /** Число доз меняется — назначение остаётся тем же лечением с другой длиной. */
    fun withTotalDoses(totalDoses: Doses): Prescription = copy(totalDoses = totalDoses)

    /** Годится ли пачка под это назначение; `null` — годится (PLAN D5). */
    fun faultOf(pkg: PackageRef): CourseSource.Fault? = CourseSource.Fault.between(pkg, dose, form)

    companion object {
        /**
         * Сколько доз лечение может назначить (решение владельца 2026-09-23): девять лет по три
         * приёма в день. Всё, что считает оставшиеся дозы, — обеспечение, конец, окно пунктов, —
         * считает их поштучно на каждое изменение базы, и назначение сверх меры роняло бы расчёт.
         *
         * Это правило **действия** — назначить, начать, изменить число, — и отказывают по нему
         * переходы. Конструктор его не проверяет: курс, записанный до правила, читается как был.
         */
        const val MAX_TOTAL_DOSES = 10_000

        /** Назначают ли столько доз: черновик и идущее лечение спрашивают это здесь, а не решают сами. */
        fun allows(totalDoses: Doses): Boolean = totalDoses.count <= MAX_TOTAL_DOSES
    }
}
