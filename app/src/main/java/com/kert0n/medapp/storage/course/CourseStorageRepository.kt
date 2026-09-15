package com.kert0n.medapp.storage.course

import androidx.annotation.CheckResult
import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.report.CourseInProgress
import com.kert0n.medapp.domain.course.CoverageReduction
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

    /**
     * Обеспечение идущего лечения — величина, которую считает курс по своему прогрессу и по
     * доступности своих пачек, как её видит человек: с очередью и без чужих броней (PLAN D4, D5).
     * План, приёмы и пачки читаются одним снимком. `null` — плана нет: лечение не начато или
     * закончено. Чужая коробка в расчёт не входит.
     */
    fun observeCoverage(id: Uuid): Flow<CourseCoverage?>

    /** То же по всем идущим лечениям сразу — списку курсов, где нехватка видна значком (H3 №13). */
    fun observeCoverages(): Flow<Map<Uuid, CourseCoverage>>

    /** Сокращения обеспечения эпизода по времени — карточке курса (PLAN D5). */
    fun observeReductions(courseId: Uuid): Flow<List<CoverageReduction>>

    /**
     * Сокращения не старше [since] — сверке уведомлений (PLAN D8). Окно есть, потому что событие
     * прошлого года сказать уже нечего, а история курса растёт: перечитывать её целиком каждым
     * проходом значило бы платить за неё вечно.
     */
    suspend fun reductionsSince(courseId: Uuid, since: Instant): List<CoverageReduction>

    /** Сокращения всех лечений с [since] — одним чтением, сверке (PLAN D8). */
    suspend fun recentReductions(since: Instant): List<CoverageReduction>

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
    @CheckResult
    suspend fun saveDraft(draft: CourseDraft, expected: Revision?): Boolean

    /**
     * Черновик уходит целиком — со временами и выбранными пачками. Отменять в нём нечего: броней у
     * него нет, пунктов он не породил (PLAN D5). `false` — черновика нет: удалён или лечение уже
     * началось, и тогда не тронуто ничего.
     */
    @CheckResult
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
    @CheckResult
    suspend fun rename(id: Uuid, title: String, note: String?): Boolean

    /** Какому активному курсу отдана пачка; `null` — она свободна (PLAN F1, F2). */
    suspend fun courseHolding(packageId: Uuid): Uuid?

    /**
     * Какие лечения держат коробку источником — по составу, а не по назначениям: назначения
     * бывают только у начатого, а состав есть и у черновика (PLAN D5). За коробкой следует
     * каждое из них (`CourseFollowing`).
     */
    suspend fun holdersOf(packageId: Uuid): List<Uuid>

    /** Идущее лечение с прогрессом — то, от чего считают потребность и зажим; `null` — плана нет или это черновик. */
    suspend fun planInProgress(id: Uuid): CourseInProgress?

    /** Событие сокращения обеспечения (PLAN D5): записывается тем, кто зажал курс, той же транзакцией. */
    suspend fun recordReduction(reduction: CoverageReduction)

    /**
     * Изменённое лечение — доза, форма, расписание, число доз, выделения под них — ложится в план,
     * его времена и снимок назначения в записи эпизода одной транзакцией, условно по редакции
     * [expected], из которой план правили (PLAN D5, F5). Состав пачек изменение не меняет: смена
     * состава — [updateSources]. `false` — плана уже нет либо он другой редакции; не тронуто ничего.
     */
    @CheckResult
    suspend fun amend(course: Course, expected: Revision): Boolean

    /**
     * Пересчитанные выделения и число доз мимо плана — условно по редакции, из которой считали
     * (PLAN D5, F5). `false` — плана уже нет; план другой редакции — ошибка вызывающего. Состав
     * пачек пересчёт не меняет: иначе выделения разошлись бы с назначениями пачек, которые здесь
     * не трогаются, — смена состава идёт через [updateSources].
     */
    @CheckResult
    suspend fun reallocate(reallocation: CourseReallocation): Boolean

    /**
     * Изменённый состав препарата — пачка привязана, отвязана или переставлена — с его
     * выделениями: источники и назначения пачек активному курсу пишутся одной транзакцией, потому
     * что это два представления одного отношения (PLAN F1, F2). Условно по редакции [expected];
     * `false` — писать некуда: плана уже нет, он другой редакции, либо состав называет коробку,
     * которой больше нет (PLAN F5). Пачку, занятую другим курсом, отвергает база.
     */
    @CheckResult
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
