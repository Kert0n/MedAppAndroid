package com.kert0n.medapp.storage.course

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.course.CoverageReduction
import com.kert0n.medapp.domain.pack.Availability
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.report.CourseInProgress
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.storage.database.chunkedForQuery
import com.kert0n.medapp.storage.intake.IntakeDao
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.pack.projectionsOf
import com.kert0n.medapp.storage.server.SyncOperationDao
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Transaction
    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun findPlan(id: Uuid): CourseStorageRow?

    /** Черновики — те, у кого имя ещё живёт здесь, то есть лечение не начато (PLAN D5). */
    @Transaction
    @Query("SELECT * FROM courses WHERE title IS NOT NULL ORDER BY updated_at DESC")
    suspend fun drafts(): List<CourseStorageRow>

    /** Идущие лечения целиком — отчётам, которые считают по всем сразу (PLAN H6). */
    @Transaction
    @Query("SELECT * FROM courses WHERE title IS NULL ORDER BY created_at")
    suspend fun plans(): List<CourseStorageRow>

    /** Номера идущих лечений: у плана нет имени — оно живёт в записи эпизода (PLAN F1). */
    @Query("SELECT id FROM courses WHERE title IS NULL")
    suspend fun planIds(): List<Uuid>

    @Transaction
    @Query("SELECT * FROM course_records WHERE id = :id")
    suspend fun findRecord(id: Uuid): CourseRecordStorageRow?

    /** Записи эпизодов по номерам — порцией, которую называет вызывающий (`chunkedForQuery`). */
    @Transaction
    @Query("SELECT * FROM course_records WHERE id IN (:ids)")
    suspend fun recordsAmong(ids: List<Uuid>): List<CourseRecordStorageRow>

    /** Аналитика читает записи: идущее и законченное лечение для неё одной формы (PLAN H6). */
    @Transaction
    @Query("SELECT * FROM course_records ORDER BY started_at DESC")
    suspend fun records(): List<CourseRecordStorageRow>

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

    @Insert
    suspend fun insertReduction(reduction: CoverageReductionStorageEntity)

    /** События сокращения обеспечения курса по времени — карточке курса и уведомлениям (PLAN D5). */
    @Query("SELECT * FROM coverage_reductions WHERE course_id = :courseId ORDER BY at")
    suspend fun reductionsOf(courseId: Uuid): List<CoverageReductionStorageEntity>

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
 * Идущие лечения с их прогрессом — то, из чего считают обеспечение и отчёты (PLAN D5, H6).
 * Пункты всех курсов читаются порциями, а не по курсу. Зовётся внутри транзакции читающего.
 */
suspend fun CourseDao.plansInProgress(intakes: IntakeDao, vocabulary: Vocabulary): List<CourseInProgress> =
    withProgress(plans().map { it.toPlan(vocabulary) }, intakes, vocabulary)

/** Одно идущее лечение с прогрессом; `null` — плана нет или это черновик. */
suspend fun CourseDao.planInProgress(id: Uuid, intakes: IntakeDao, vocabulary: Vocabulary): CourseInProgress? {
    val plan = findPlan(id)?.takeUnless { it.isDraft }?.toPlan(vocabulary) ?: return null
    return withProgress(listOf(plan), intakes, vocabulary).single()
}

private suspend fun withProgress(courses: List<Course>, intakes: IntakeDao, vocabulary: Vocabulary): List<CourseInProgress> {
    val byCourse = courses.map { it.id }.chunkedForQuery()
        .flatMap { intakes.ofCourses(it) }
        .map { it.toDomain(vocabulary) as CourseIntake }
        .groupBy { it.courseId }
    return courses.map { CourseInProgress(it, CourseProgress.of(byCourse[it.id].orEmpty())) }
}

/**
 * Расклад «сколько доступно мне» по пачкам лечений — от того же числа, которое видит человек: с
 * незакрытыми командами поверх и без чужих броней (PLAN D4). Пачки, которой уже нет, в раскладе
 * ничего: курс вот-вот потеряет её своим переходом. Проекции всех пачек всех лечений — одной
 * порцией, а не по курсу. Зовётся внутри транзакции читающего.
 */
suspend fun PackageDao.availabilityOf(
    courses: List<Course>,
    queue: SyncOperationDao,
    intakes: IntakeDao,
    vocabulary: Vocabulary
): Map<Uuid, Availability> {
    val ids = courses.flatMap { course -> course.sources.map { it.pkg.id } }.distinct()
    val living = ids.chunkedForQuery().flatMap { among(it) }.map { it.toDomain(vocabulary) }
    val projected = projectionsOf(living, queue, intakes, vocabulary).associateBy { it.id }
    return courses.associate { course ->
        course.id to Availability(
            course.sources.associate { source ->
                source.pkg.id to (projected[source.pkg.id]?.availability?.availableToMe ?: Quantity.zero(source.pkg.unit))
            }
        )
    }
}

/**
 * Курс следует за коробкой — **одна дверь** для всех, кто коробку изменил: человек пересчётом или
 * разовым приёмом, сосед расходом или бронью, пришедшими снимком или ответом на команду (PLAN D5,
 * E4). Каждое идущее лечение, держащее пачку [packageId], зажимает выделения под то, что доступно
 * ему сейчас, — `Course.clamped` от того же числа, которое видит человек, — и пишется условно по
 * своей редакции. Расписание, доза и даты не трогаются. Зажимать нечего — курс не пишется, и
 * редакция не растёт: снимок, согласный с нами, ничего не меняет.
 *
 * Возвращает пары «до и после» — брони разницей ставит вызывающий, который владеет транзакцией.
 * Зовётся внутри уже открытой транзакции того, кто коробку изменил.
 */
suspend fun CourseDao.followBox(
    packageId: Uuid,
    packages: PackageDao,
    intakes: IntakeDao,
    queue: SyncOperationDao,
    vocabulary: Vocabulary,
    at: Instant
): List<CourseFollowed> {
    val ref = packages.find(packageId)?.toDomain(vocabulary)?.ref ?: return emptyList()
    val followed = mutableListOf<CourseFollowed>()
    for (courseId in coursesHolding(packageId)) {
        val row = findPlan(courseId) ?: continue
        if (row.isDraft) {
            followTheBoxAsADraft(row.toDraft(vocabulary), ref, at)
            continue
        }
        val plan = planInProgress(courseId, intakes, vocabulary) ?: continue
        val course = plan.course
        // Совместимость — первой: отключённый источник в расклад не входит, и считать по нему нечего.
        val compatible = when (val fault = course.prescription.faultOf(ref)) {
            null -> if (courseHolding(packageId) == null || courseHolding(packageId) == courseId) course.restoreSource(ref, at) else course
            else -> course.faultSource(ref, fault, at)
        }
        val availability = packages.availabilityOf(listOf(compatible), queue, intakes, vocabulary).getValue(course.id)
        val required = compatible.remainingDoses(plan.progress)
        val clamped = compatible.clamped(required, availability, at)
        if (clamped === course) continue
        check(updateAllocations(clamped.toStorageEntity(), clamped.medicine.toSourceStorageEntities(clamped.id), course.revision)) {
            "план прочитан этой же транзакцией"
        }
        // Обеспеченных доз стало меньше — событие (PLAN D5). До — выделенное прежним курсом:
        // после каждого зажима выделение и есть обеспечение; после — обеспечение нового.
        val coveredBefore = minOf(course.allocatedDosesTotal, required)
        val coveredAfter = clamped.coverage(plan.progress, availability).coveredDoses
        if (coveredAfter < coveredBefore) {
            insertReduction(CoverageReduction(Uuid.random(), courseId, packageId, coveredBefore, coveredAfter, at).toStorageEntity())
        }
        // Назначение коробки следует за пригодностью источника: отключённый её не держит.
        if (clamped.medicine.faultOf(ref) != null) releasePackage(packageId)
        else if (course.medicine.faultOf(ref) != null) assignPackage(ActivePackageAssignmentStorageEntity(packageId, courseId))
        followed += CourseFollowed(course, clamped)
    }
    return followed
}

/** Черновик за коробкой следует только совместимостью: выделений и броней у него нет (PLAN D5). */
private suspend fun CourseDao.followTheBoxAsADraft(draft: CourseDraft, ref: PackageRef, at: Instant) {
    val followed = when (val fault = draft.faultOf(ref)) {
        null -> draft.restoreSource(ref, at)
        else -> draft.faultSource(ref, fault, at)
    }
    if (followed === draft) return
    saveCourse(
        course = followed.toStorageEntity(),
        times = followed.schedule?.toTimeStorageEntities(followed.id).orEmpty(),
        sources = followed.medicine.toSourceStorageEntities(followed.id)
    )
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
