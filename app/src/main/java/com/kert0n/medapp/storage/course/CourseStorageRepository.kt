package com.kert0n.medapp.storage.course

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/**
 * Хранение лечения. Черновик и живой план лежат одной таблицей и различаются тем, где живёт имя,
 * поэтому спрашивают их порознь: у экрана черновика и экрана курса разные вопросы (PLAN D5, F1).
 *
 * Потоки несут проекции — величины для экрана; сущность отдают только `find*`, и действительна
 * она в транзакции сценария, который её читал (PLAN H1).
 */
interface CourseStorageRepository {

    fun observeDrafts(): Flow<List<CourseDraftProjection>>

    fun observePlan(id: Uuid): Flow<CourseProjection?>

    suspend fun findDraft(id: Uuid): CourseDraft?

    suspend fun findPlan(id: Uuid): Course?

    /** Номера идущих лечений — тому, кто поддерживает их календарь (PLAN F4). */
    suspend fun planIds(): List<Uuid>

    /**
     * Черновик целиком: назначение, времена и источники по порядку. Запись условна: лечение,
     * начатое между открытием экрана и сохранением, черновиком поверх не затирается — активация
     * уничтожает черновик, а не прячет его, а конец лечения уничтожает и план. [expected] —
     * редакция, из которой черновик правили; `null` — черновик новый, и под его номером ещё нет ни
     * черновика, ни эпизода. `false` — писать некуда: черновика больше нет, он другой редакции, или
     * номер уже занят (PLAN F5).
     */
    suspend fun saveDraft(draft: CourseDraft, expected: Revision?): Boolean

    /**
     * Черновик уходит целиком — со временами и выбранными пачками. Отменять в нём нечего: броней у
     * него нет, пунктов он не породил (PLAN D5). `false` — черновика нет: удалён или лечение уже
     * началось, и тогда не тронуто ничего.
     */
    suspend fun discardDraft(id: Uuid): Boolean

    /** Аналитика читает записи: идущее и законченное лечение для неё одной формы (PLAN H6). */
    fun observeRecords(): Flow<List<CourseRecordProjection>>

    fun observeRecord(id: Uuid): Flow<CourseRecordProjection?>

    suspend fun findRecord(id: Uuid): CourseRecord?

    /**
     * Название и заметка эпизода — единственное, что человек правит в записи напрямую: назначение
     * и исход задают активация и конец лечения (PLAN D5). `false` — записи нет.
     *
     * Общей записи здесь нет намеренно: экран, загрузивший открытый эпизод, сохранял бы его
     * целиком уже после конца лечения и возвращал бы запись в открытое состояние.
     */
    suspend fun rename(id: Uuid, title: String, note: String?): Boolean

    /** Какому активному курсу отдана пачка; `null` — она свободна (PLAN F1, F2). */
    suspend fun courseHolding(packageId: Uuid): Uuid?

    /**
     * Число доз, поправленное у плана: ложится в план и в снимок записи эпизода одной
     * транзакцией, условно по редакции [expected], из которой план правили. `false` — плана
     * уже нет либо он другой редакции; ни одна из двух строк тогда не тронута.
     */
    suspend fun setTotalDoses(course: Course, expected: Revision): Boolean

    /**
     * Пересчитанные выделения и число доз мимо плана — условно по редакции, из которой считали
     * (PLAN D5, F5). `false` — плана уже нет; план другой редакции — ошибка вызывающего. Состав
     * пачек пересчёт не меняет: иначе выделения разошлись бы с назначениями пачек, которые здесь
     * не трогаются, — смена состава идёт через [updateSources].
     */
    suspend fun reallocate(reallocation: CourseReallocation): Boolean

    /**
     * Изменённый состав препарата — пачка привязана, отвязана или переставлена — с его
     * выделениями: источники и назначения пачек активному курсу пишутся одной транзакцией, потому
     * что это два представления одного отношения (PLAN F1, F2). Условно по редакции [expected];
     * `false` — писать некуда: плана уже нет, он другой редакции, либо состав называет коробку,
     * которой больше нет (PLAN F5). Пачку, занятую другим курсом, отвергает база.
     */
    suspend fun updateSources(course: Course, expected: Revision): Boolean

    /**
     * Активация: план и запись эпизода заводятся **одной** транзакцией и с одним назначением.
     * Ни того ни другого в базе поодиночке не бывает (PLAN F5).
     *
     * Здесь же занимаются пачки и материализуется окно расписания; команды броней ставит служба
     * очереди той же транзакцией. Занятая другим курсом пачка отвергается первичным ключом
     * назначения, а не проверкой перед вставкой, и тогда транзакция откатывается целиком.
     */
    suspend fun activate(activation: CourseDraft.Activation, planned: List<CourseIntake> = emptyList())

    /**
     * Конец лечения: запись закрывается **вместе** с удалением плана. Строки `courses` после
     * этого не существует, а `course_records` остаётся навсегда (PLAN D5, F5).
     *
     * Принимает [CourseCompletion.Closing] целиком: закрытая запись и отменённые пункты — половины
     * одного события, и порознь их не бывает. Разобрать их на аргументы значило бы позволить
     * записать одну без другой.
     *
     * Будущие пункты отменяются, назначения освобождаются; снятие броней ставит служба очереди.
     */
    suspend fun close(closing: CourseCompletion.Closing)
}
