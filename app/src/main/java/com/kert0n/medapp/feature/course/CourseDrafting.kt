package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.domain.course.CourseSchedule
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.doses
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Черновик лечения: «записал у врача, куплю завтра» (PLAN D5). Законное сохраняемое состояние —
 * названия уже достаточно; назначение собирается по частям, а пачки подключаются предварительным
 * выбором. **Ничего не занимает**: броней у черновика нет, назначений пачек нет, и команд серверу
 * он не ставит — даже когда пачка лежит на общей полке.
 *
 * Правится **названными действиями**, а не готовым черновиком (PLAN F5): каждое применяется
 * доменным переходом к черновику, прочитанному в той же транзакции, а подключаемая пачка — живая,
 * прочитанная там же. Запись условна по редакции, из которой правили: вторая правка поверх первой
 * не ложится, и черновик, ставший лечением, не затирается.
 */
class CourseDrafting @Inject constructor(
    private val courses: CourseStorageRepository,
    private val packages: PackageStorageRepository,
    private val transactions: Transactions,
    private val clock: Clock
) {

    /** Новый черновик — с одним названием и заметкой. */
    suspend fun create(title: String, note: String? = null): CourseDraftProjection = transactions.run {
        val now = clock.instant()
        val draft = CourseDraft(id = Uuid.random(), title = title, note = note, createdAt = now, updatedAt = now)
        check(courses.saveDraft(draft, expected = null)) { "номер нового черновика придуман только что" }
        draft.projection()
    }

    /**
     * Правки [edits] по порядку, одной транзакцией: либо ложатся все, либо ни одна. [expected] —
     * редакция, которую видел экран.
     */
    suspend fun edit(id: Uuid, expected: Revision, edits: List<Edit>): Outcome = transactions.run {
        val draft = courses.findDraft(id) ?: return@run Outcome.Gone
        if (draft.revision != expected) return@run Outcome.Stale
        val now = clock.instant()
        var edited = draft
        for (edit in edits) {
            edited = when (edit) {
                is Edit.Rename -> edited.rename(edit.title, edit.note, now)
                is Edit.SetDose -> edited.setDose(edit.dose, now).getOrElse { return@run rejected(it) }
                is Edit.SetForm -> edited.setForm(edit.form, now).getOrElse { return@run rejected(it) }
                is Edit.SetSchedule -> edited.setSchedule(edit.schedule, now).getOrElse { return@run rejected(it) }
                is Edit.SetTotalDoses -> edited.setTotalDoses(edit.totalDoses, now)
                is Edit.Attach -> {
                    // Источник — только коробка, которая у человека есть и которой можно пользоваться.
                    val pkg = packages.find(edit.packageId)?.takeIf { it.status.allowsUse }
                        ?: return@run Outcome.PackageUnusable
                    edited.attach(pkg, edit.doses, now).getOrElse { return@run rejected(it) }
                }
                is Edit.Detach -> {
                    val source = edited.sources.firstOrNull { it.pkg.id == edit.packageId } ?: continue
                    edited.detach(source.pkg, now)
                }
                is Edit.Reorder -> edited.reorder(edit.from, edit.to, now)
                is Edit.Allocate -> {
                    val source = edited.sources.firstOrNull { it.pkg.id == edit.packageId }
                        ?: return@run Outcome.PackageUnusable
                    edited.allocate(source.pkg, edit.doses, now).getOrElse { return@run rejected(it) }
                }
            }
        }
        if (edited === draft) return@run Outcome.Saved(draft.projection())

        // Больше, чем лечению нужно, не выделяют — отказом, а не подрезкой: снять разницу с чужой
        // строки за человека нельзя (C1 «Ползунок»). Уменьшение числа приёмов сюда не попадает —
        // оно снимает лишнее само (PLAN D5). Предел нарушает прежде всего та пачка, которой
        // прибавили: её человек и двигал.
        edited.sources
            .sortedByDescending { source -> draft.sources.none { it.pkg == source.pkg && it.allocatedDoses >= source.allocatedDoses } }
            .firstOrNull { source -> edited.needLeftFor(source.pkg)?.let { source.allocatedDoses > it } == true }
            ?.let { return@run Outcome.BeyondLimit(it.pkg.id, edited.needLeftFor(it.pkg) ?: 0.doses) }

        if (!courses.saveDraft(edited, expected)) return@run Outcome.Gone
        Outcome.Saved(edited.projection())
    }

    /** Черновик удаляется — отменять в нём нечего (PLAN D5). `false` — удалять нечего. */
    suspend fun discard(id: Uuid): Boolean = courses.discardDraft(id)

    private fun rejected(failure: Throwable): Outcome =
        Outcome.Rejected((failure as? CourseRejected ?: throw failure).reason)

    /** Действие над черновиком — то, что человек сделал на экране, а не состояние формы. */
    sealed interface Edit {
        data class Rename(val title: String, val note: String?) : Edit
        data class SetDose(val dose: Dose) : Edit
        data class SetForm(val form: DosageForm) : Edit
        data class SetSchedule(val schedule: CourseSchedule) : Edit
        data class SetTotalDoses(val totalDoses: Doses) : Edit
        data class Attach(val packageId: Uuid, val doses: Doses) : Edit
        data class Detach(val packageId: Uuid) : Edit
        data class Reorder(val from: Int, val to: Int) : Edit
        data class Allocate(val packageId: Uuid, val doses: Doses) : Edit
    }

    /**
     * Чем кончилось. Сохранили — экран показывает записанное; черновика нет (удалён или лечение уже
     * началось) — закрыть; устарел — перечитать; домен отказал — причина по месту; пачке выделено
     * больше предела — назвать её и предел; пачки нет или ею пользоваться нельзя — выбрать другую.
     */
    sealed interface Outcome {
        data class Saved(val draft: CourseDraftProjection) : Outcome
        data object Gone : Outcome
        data object Stale : Outcome
        data class Rejected(val reason: CourseRejected.Reason) : Outcome
        data class BeyondLimit(val packageId: Uuid, val limit: Doses) : Outcome
        data object PackageUnusable : Outcome
    }
}
