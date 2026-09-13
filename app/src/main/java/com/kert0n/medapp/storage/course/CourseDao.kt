package com.kert0n.medapp.storage.course

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.value.Vocabulary
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Transaction
    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun findPlan(id: Uuid): CourseStorageRow?

    @Transaction
    @Query("SELECT * FROM courses WHERE id = :id")
    fun observePlan(id: Uuid): Flow<CourseStorageRow?>

    /** Черновики — те, у кого имя ещё живёт здесь, то есть лечение не начато (PLAN D5). */
    @Transaction
    @Query("SELECT * FROM courses WHERE title IS NOT NULL ORDER BY updated_at DESC")
    fun observeDrafts(): Flow<List<CourseStorageRow>>

    @Transaction
    @Query("SELECT * FROM courses WHERE title IS NULL ORDER BY created_at")
    fun observePlans(): Flow<List<CourseStorageRow>>

    /** Номера идущих лечений: у плана нет имени — оно живёт в записи эпизода (PLAN F1). */
    @Query("SELECT id FROM courses WHERE title IS NULL")
    suspend fun planIds(): List<Uuid>

    @Transaction
    @Query("SELECT * FROM course_records WHERE id = :id")
    suspend fun findRecord(id: Uuid): CourseRecordStorageRow?

    @Transaction
    @Query("SELECT * FROM course_records WHERE id = :id")
    fun observeRecord(id: Uuid): Flow<CourseRecordStorageRow?>

    /** Записи эпизодов по номерам — порцией, которую называет вызывающий (`chunkedForQuery`). */
    @Transaction
    @Query("SELECT * FROM course_records WHERE id IN (:ids)")
    suspend fun recordsAmong(ids: List<Uuid>): List<CourseRecordStorageRow>

    /** Аналитика читает записи: идущее и законченное лечение для неё одной формы (PLAN H6). */
    @Transaction
    @Query("SELECT * FROM course_records ORDER BY started_at DESC")
    fun observeRecords(): Flow<List<CourseRecordStorageRow>>

    /**
     * Черновик целиком: план, его времена и его источники. Времена и источники переписываются
     * заменой — редакция черновика описывает набор, а не разницу с прошлым набором.
     */
    @Transaction
    suspend fun saveCourse(
        course: CourseStorageEntity,
        times: List<CourseTimeStorageEntity>,
        sources: List<CourseSourceStorageEntity>
    ) {
        upsertCourse(course)
        deleteSourcesOf(course.id)
        deleteTimesOf(course.id)
        insertTimes(times)
        insertSources(sources)
    }

    /**
     * Пересчитанные выделения живого плана. Запись условна по редакции: план, закрытый или уже
     * пересчитанный между чтением и записью, не возвращается и не переписывается результатом,
     * посчитанным из прошлого состава — ноль изменённых строк значит, что писать некуда
     * (PLAN D5, F5). Состав из коробок, которых больше нет, тоже некуда писать: курс, прочитанный
     * до того, как коробку выбросили, не воскрешает её источником — ответ `false`, а не
     * исключение ключа.
     *
     * Меняются только редакция, время правки, источники и число доз мимо плана: доза и
     * расписание меняет изменение лечения, и пересчёт обеспечения их не касается.
     */
    @Transaction
    suspend fun updateAllocations(
        course: CourseStorageEntity,
        sources: List<CourseSourceStorageEntity>,
        expected: Revision
    ): Boolean {
        val named = sources.map { it.packageId }
        if (livingPackagesAmong(named).size != named.size) return false
        val revised = reviseIfRevisionIs(
            course.id, expected.number, course.revision, course.takenOffPlan, course.updatedAt
        )
        if (revised == 0) {
            // Ноль строк законен ровно в одном случае: плана больше нет, писать некуда. Живой
            // план другой редакции — пересчёт из устаревшего состава, и молча пропустить его
            // нельзя: транзакция вокруг уже записала расход, обеспечение которого он и считал.
            check(findPlan(course.id) == null) {
                "выделения посчитаны из редакции ${expected.number}, а план уже другой"
            }
            return false
        }
        deleteSourcesOf(course.id)
        insertSources(sources)
        return true
    }

    @Query(
        "UPDATE courses SET revision = :revision, taken_off_plan = :takenOffPlan, " +
            "updated_at = :updatedAt WHERE id = :id AND revision = :expected"
    )
    suspend fun reviseIfRevisionIs(
        id: Uuid,
        expected: Long,
        revision: Long,
        takenOffPlan: Int,
        updatedAt: Instant
    ): Int

    @Upsert
    suspend fun upsertCourse(course: CourseStorageEntity)

    @Upsert
    suspend fun upsertRecord(record: CourseRecordStorageEntity)

    /** Правится только то, что человек и назвал: назначение, начало и исход остаются на месте. */
    @Query("UPDATE course_records SET title = :title, note = :note WHERE id = :id")
    suspend fun rename(id: Uuid, title: String, note: String?): Int

    @Insert
    suspend fun insertTimes(times: List<CourseTimeStorageEntity>)

    @Insert
    suspend fun insertSources(sources: List<CourseSourceStorageEntity>)

    @Query("DELETE FROM course_times WHERE course_id = :courseId")
    suspend fun deleteTimesOf(courseId: Uuid)

    @Query("DELETE FROM course_sources WHERE course_id = :courseId")
    suspend fun deleteSourcesOf(courseId: Uuid)

    @Query("SELECT package_id FROM course_sources WHERE course_id = :courseId")
    suspend fun sourcePackagesOf(courseId: Uuid): List<Uuid>

    /**
     * Какое лечение держит эту коробку источником — по составу, а не по назначениям: назначения
     * бывают только у начатого, а состав есть и у черновика (PLAN D5, F1).
     */
    @Query("SELECT course_id FROM course_sources WHERE package_id = :packageId")
    suspend fun coursesHolding(packageId: Uuid): List<Uuid>

    /** Какие из названных коробок ещё есть: источником бывает только живая (PLAN D3). */
    @Query("SELECT id FROM packages WHERE id IN (:packageIds)")
    suspend fun livingPackagesAmong(packageIds: List<Uuid>): List<Uuid>

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deletePlan(id: Uuid)

    /**
     * Назначение пачки активному курсу. Вставка без стратегии конфликта намеренно: второе
     * назначение должно быть отвергнуто базой, а не пережить проверку «а нет ли уже» (PLAN F2).
     */
    @Insert
    suspend fun assignPackage(assignment: ActivePackageAssignmentStorageEntity)

    @Query("SELECT course_id FROM active_package_assignments WHERE package_id = :packageId")
    suspend fun courseHolding(packageId: Uuid): Uuid?

    @Query("SELECT * FROM active_package_assignments WHERE course_id = :courseId")
    suspend fun assignmentsOf(courseId: Uuid): List<ActivePackageAssignmentStorageEntity>

    @Query("DELETE FROM active_package_assignments WHERE package_id = :packageId")
    suspend fun releasePackage(packageId: Uuid)

    @Query("DELETE FROM active_package_assignments WHERE course_id = :courseId")
    suspend fun releaseAssignmentsOf(courseId: Uuid)
}

/**
 * Источник не переживает коробку: **каждое** лечение, державшее пачку [pkg], теряет её доменным
 * переходом — с ростом редакции и освобождением назначения, — а не молча каскадом схемы
 * (PLAN D5, F5). Зовётся один раз, из двери конца коробки, и только оттуда.
 *
 * Лечение ищется по составу, а не по назначениям: назначения бывают только у начатого, а состав
 * есть и у черновика, и вырезанный каскадом источник черновика человек обнаружил бы сам, вернувшись
 * к недоделанному курсу.
 */
suspend fun CourseDao.releaseSource(pkg: PackageRef, vocabulary: Vocabulary, at: Instant) {
    for (courseId in coursesHolding(pkg.id)) {
        val row = findPlan(courseId) ?: continue
        if (row.isDraft) {
            val draft = row.toDraft(vocabulary).detach(pkg, at)
            saveCourse(
                course = draft.toStorageEntity(),
                times = draft.schedule?.toTimeStorageEntities(draft.id).orEmpty(),
                sources = draft.medicine.toSourceStorageEntities(draft.id)
            )
        } else {
            val plan = row.toPlan(vocabulary)
            val detached = plan.detach(pkg, at)
            // Ноль изменённых строк здесь незаконен: план прочитан этой же транзакцией. Молча
            // пропустить значило бы оставить курс с источником, которого уже нет.
            check(
                updateAllocations(
                    detached.toStorageEntity(),
                    detached.medicine.toSourceStorageEntities(detached.id),
                    plan.revision
                )
            ) { "курс $courseId прочитан этой же транзакцией, а выделения писать некуда" }
        }
    }
    releasePackage(pkg.id)
}
