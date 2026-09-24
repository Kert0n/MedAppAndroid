package com.kert0n.medapp.storage

import com.kert0n.medapp.feature.course.CourseRecords
import com.kert0n.medapp.feature.intake.IntakeRecords
import com.kert0n.medapp.feature.medkits.MedKitRecords
import com.kert0n.medapp.feature.notification.ReminderRecords
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.feature.report.ReportReadings
import java.io.File
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Хранению передаётся **действие**, а не прочитанный экземпляр (PLAN F5). Правило проект знает
 * давно — `CourseDraft.Activation`, `CourseCompletion.Closing`, `IntakeOutcome`,
 * `PackageAdjustment`, — и KDoc `IntakeOutcome` формулирует его прямо: «Готового нового состояния
 * пачки сюда не передают: посчитанное по прочитанному когда-то раньше, оно легло бы поверх
 * нынешнего».
 *
 * Нарушали его дважды, и оба раза молча: `discard(packageId)` брал идентификатор и ничего больше,
 * поэтому спутники конца коробки собирались вызывающими вразнобой, а `published(medKit)` писал
 * аптечку, прочитанную **до сети**, и терял сделанное человеком, пока сеть шла. Ни то ни другое
 * не видно в ревью без такой проверки — как не видно каскада, поставленного «на всякий случай»
 * (`ForeignKeysTest`).
 *
 * Поэтому каждый метод порта хранения назван здесь своей формой **и подписью**. Одного имени мало:
 * метод с прежним именем, но принимающий теперь прочитанную сущность вместо действия, или его
 * перегрузка рядом прошли бы проверку по имени незамеченными (CodeRabbit на #17). Незнакомый метод
 * или изменившаяся подпись — падение: договор пополняется явно, а не молчаливым умолчанием.
 */
class WriteContractTest {

    /** Чем метод берёт то, что пишет, — и почему этого достаточно. */
    private enum class Shape {

        /** Спрашивает, не меняет. */
        READ,

        /** Значение-действие: всё, чего порознь не бывает, приходит одним типом. */
        ACTION,

        /** Заведение: спорить не с чем, вещи ещё не было. */
        CREATION,

        /** Сущность вместе с редакцией, из которой её правили: устаревшее не запишется. */
        GUARDED,

        /** Идентификатор и названные поля: прочитанный экземпляр не передаётся вовсе. */
        NAMED_FIELDS,

        /** Пришедшее с провода: истина по нему — сервер, и спорить с ним нечем. */
        SNAPSHOT,

        /**
         * Прочитанный экземпляр, который читает, меняет и пишет **один и тот же узкий шаг внутри
         * своей транзакции**. Редакции здесь нет и не нужно: вклиниться между чтением и записью
         * нельзя — Room берёт замок записи на входе в транзакцию (PLAN C1, F5). Защита держится не
         * подписью метода, а тем, **кто** его зовёт, поэтому владельцы названы поимённо ниже.
         */
        IN_TRANSACTION,

        /**
         * Прочитанный экземпляр без токена редакции — **долг**, а не форма. Каждый такой метод
         * назван ниже вместе с причиной и сроком; новый добавить молча нельзя.
         */
        UNGUARDED
    }

    /** Форма метода и его подпись — `(параметры): возврат` простыми именами типов. */
    private data class Clause(val shape: Shape, val signature: String)

    private infix fun Shape.by(signature: String) = Clause(this, signature)

    private val contract: Map<String, Clause> = mapOf(
        // Лекарство
        "PackageReadings.observe" to (Shape.READ by "(Uuid): Flow<PackageProjection>"),
        "PackageRecords.find" to (Shape.READ by "(Uuid): Package"),
        "PackageRecords.projection" to (Shape.READ by "(Uuid): PackageProjection"),
        "PackageReadings.list" to (Shape.READ by "(PackageQuery, LocalDate): Flow<List<PackageProjection>>"),
        "PackageRecords.contentsOf" to (Shape.READ by "(Uuid): List<Package>"),
        "PackageRecords.answersToServer" to (Shape.READ by "(Uuid): Boolean"),
        "PackageRecords.availabilityFor" to (Shape.READ by "(Course): Availability"),
        "PackageRecords.add" to (Shape.CREATION by "(Package): Unit"),
        "PackageRecords.describe" to (Shape.NAMED_FIELDS by "(Uuid, PackageFacts): Boolean"),
        "PackageRecords.saveClaims" to (Shape.NAMED_FIELDS by "(Uuid, Claims): Unit"),
        "PackageRecords.mark" to (Shape.NAMED_FIELDS by "(Uuid, PackageStatus, Uuid): Boolean"),
        "PackageRecords.end" to (Shape.ACTION by "(PackageEnding, Instant): Boolean"),
        "PackageRecords.adjust" to (Shape.ACTION by "(PackageAdjustment, CourseReallocation, Instant): Boolean"),
        // Отчёты
        "ReportReadings.observeSpending" to (Shape.READ by "(SpendingPeriod, ZoneId): Flow<Spending>"),
        "ReportReadings.observeFutureSpending" to (Shape.READ by "(SpendingHorizon): Flow<FutureSpending>"),
        "ReportReadings.observeStockSummary" to (Shape.READ by "(): Flow<StockSummary>"),
        "ReportReadings.observeDayPlan" to (Shape.READ by "(LocalDate, ZoneId): Flow<DayPlan>"),
        // Лечение
        "CourseReadings.observeDrafts" to (Shape.READ by "(): Flow<List<CourseDraftProjection>>"),
        "CourseReadings.observePlan" to (Shape.READ by "(Uuid): Flow<CourseProjection>"),
        "CourseReadings.observeCoverage" to (Shape.READ by "(Uuid): Flow<CourseCoverage>"),
        "CourseReadings.observeCoverages" to (Shape.READ by "(): Flow<Map<Uuid, CourseCoverage>>"),
        "CourseReadings.observeReductions" to (Shape.READ by "(Uuid): Flow<List<CoverageReduction>>"),
        "CourseRecords.reductionsSince" to (Shape.READ by "(Uuid, Instant): List<CoverageReduction>"),
        "CourseRecords.recentReductions" to (Shape.READ by "(Instant): List<CoverageReduction>"),
        "CourseReadings.observeRecords" to (Shape.READ by "(): Flow<List<CourseRecordProjection>>"),
        "CourseReadings.observeRecord" to (Shape.READ by "(Uuid): Flow<CourseRecordProjection>"),
        "CourseRecords.findDraft" to (Shape.READ by "(Uuid): CourseDraft"),
        "CourseRecords.findPlan" to (Shape.READ by "(Uuid): Course"),
        "CourseRecords.planIds" to (Shape.READ by "(): List<Uuid>"),
        "CourseRecords.findRecord" to (Shape.READ by "(Uuid): CourseRecord"),
        "CourseRecords.courseHolding" to (Shape.READ by "(Uuid): Uuid"),
        "CourseRecords.holdersOf" to (Shape.READ by "(Uuid): List<Uuid>"),
        "CourseRecords.planInProgress" to (Shape.READ by "(Uuid): CourseInProgress"),
        "CourseRecords.recordReduction" to (Shape.CREATION by "(CoverageReduction): Unit"),
        "CourseRecords.rename" to (Shape.NAMED_FIELDS by "(Uuid, String, String): Boolean"),
        // `long` — редакция: `value class Revision` на JVM разворачивается в своё число.
        "CourseRecords.amend" to (Shape.GUARDED by "(Course, long): Boolean"),
        "CourseRecords.updateSources" to (Shape.GUARDED by "(Course, long): Boolean"),
        "CourseRecords.reallocate" to (Shape.ACTION by "(CourseReallocation): Boolean"),
        "CourseRecords.activate" to (Shape.ACTION by "(CourseDraft\$Activation, List<CourseIntake>): Unit"),
        "CourseRecords.close" to (Shape.ACTION by "(CourseCompletion\$Closing): Unit"),
        // Черновик записывается целиком, но условно по редакции, из которой его правили; новый —
        // только туда, где под его номером ещё ничего нет (PLAN F5).
        "CourseRecords.saveDraft" to (Shape.GUARDED by "(CourseDraft, Revision): Boolean"),
        "CourseRecords.discardDraft" to (Shape.NAMED_FIELDS by "(Uuid): Boolean"),
        // Аптечка
        "MedKitReadings.observeAll" to (Shape.READ by "(LocalDate): Flow<List<MedKitProjection>>"),
        "MedKitReadings.observe" to (Shape.READ by "(Uuid, LocalDate): Flow<MedKitProjection>"),
        "MedKitRecords.find" to (Shape.READ by "(Uuid): MedKit"),
        "MedKitRecords.delete" to (Shape.NAMED_FIELDS by "(Uuid): Boolean"),
        "MedKitRecords.mark" to (Shape.NAMED_FIELDS by "(Uuid, MedKitStatus): Boolean"),
        "MedKitRecords.applyServerParticipants" to (Shape.NAMED_FIELDS by "(Uuid, long, Instant): Unit"),
        // Все серверные полки — утратой доступа разом: решение о нечитаемой учётке (G2).
        "MedKitRecords.published" to (Shape.READ by "(): List<Uuid>"),
        "MedKitRecords.loseAccess" to (Shape.NAMED_FIELDS by "(Uuid, Instant): Unit"),
        "MedKitRecords.add" to (Shape.CREATION by "(MedKit): Unit"),
        "MedKitRecords.describe" to (Shape.NAMED_FIELDS by "(Uuid, String, String): Boolean"),
        // Приём
        "IntakeReadings.observeOfCourse" to (Shape.READ by "(Uuid): Flow<List<IntakeProjection>>"),
        "IntakeReadings.observeOfPackage" to (Shape.READ by "(Uuid): Flow<List<IntakeProjection>>"),
        "IntakeReadings.observeOfIds" to (Shape.READ by "(Set<Uuid>): Flow<List<IntakeProjection>>"),
        "IntakeRecords.ofCourse" to (Shape.READ by "(Uuid): List<? extends Intake>"),
        "IntakeRecords.find" to (Shape.READ by "(Uuid): Intake"),
        "IntakeAccounts.of" to (Shape.READ by "(Uuid): IntakeSyncState"),
        "IntakeRecords.plannedBefore" to (Shape.READ by "(Instant): List<CourseIntake>"),
        "IntakeRecords.save" to (Shape.ACTION by "(RecordedIntake): Unit"),
        "IntakeRecords.record" to (Shape.ACTION by "(IntakeOutcome): Boolean"),
        "IntakeRecords.materialise" to (Shape.CREATION by "(List<CourseIntake>): List<Uuid>"),
        "IntakeRecords.prunePlanned" to (Shape.NAMED_FIELDS by "(Uuid, Set<ScheduledOccurrence>): List<Uuid>"),
        "ReminderRecords.changes" to (Shape.READ by "(): Flow<Unit>"),
        "ReminderRecords.groundsChanged" to (Shape.READ by "(): Flow<Unit>"),
        "ReminderRecords.find" to (Shape.READ by "(NotificationKey): Reminder"),
        "ReminderRecords.findAll" to (Shape.READ by "(Collection<NotificationKey>): List<Reminder>"),
        "ReminderRecords.awaiting" to (Shape.READ by "(NoticeDelivery): List<Reminder>"),
        "ReminderReadings.observeAwaiting" to (Shape.READ by "(NoticeDelivery): Flow<List<PendingNotice>>"),
        "ReminderRecords.groundless" to (Shape.READ by "(): List<Reminder>"),
        "ReminderRecords.stale" to (Shape.READ by "(Instant): List<Reminder>"),
        "ReminderRecords.ofKinds" to (Shape.READ by "(Collection<? extends NotificationKind>): List<Reminder>"),
        // Обязательство считает своё состояние само и приходит сюда целиком: спорить с ним нечем.
        "ReminderRecords.saveAll" to (Shape.IN_TRANSACTION by "(Collection<Reminder>): Unit"),
        // Удаляется то, что перечитано той же транзакцией: отозванное, воскрешённое между гашением и
        // удалением, живёт дальше.
        "ReminderRecords.deleteAll" to (Shape.IN_TRANSACTION by "(Collection<NotificationKey>): Unit")
    )

    /**
     * Долг назван поимённо, и сейчас он пуст: новый метод, принимающий прочитанный экземпляр без
     * редакции, в список молча не попадёт — его придётся приписать сюда руками и объяснить.
     */
    private val known: Set<String> = emptySet()

    /**
     * Кому позволено читать и писать обязательство — с именем шага-владельца. Список закрыт: пятое
     * место, взявшееся писать прочитанный экземпляр, либо открывает свою транзакцию и приписывается
     * сюда, либо объявляется долгом. Молча назвать такую запись действием больше нельзя.
     */
    private val inTransaction: Map<String, Set<String>> = mapOf(
        "ReminderRecords.saveAll" to setOf(
            // Завести недостающее и воскресить отозванное.
            "feature/notification/ReminderPromising.kt",
            // Снять обещанное, у которого не стало повода.
            "feature/notification/ReminderWithdrawal.kt",
            // Отложить по кнопке из шторки.
            "feature/notification/ReminderAnswering.kt",
            // Записать исход показа — перечитав: пока шла система, обязательство могли изменить.
            "feature/notification/ReminderOutbox.kt"
        ),
        "ReminderRecords.deleteAll" to setOf(
            // Забыть отозванное и давнее — перечитав: воскрешённое между гашением и удалением живёт.
            "feature/notification/ReminderOutbox.kt"
        )
    )

    private val sources: File = listOf(
        File("src/main/java/com/kert0n/medapp"),
        File("app/src/main/java/com/kert0n/medapp")
    ).firstOrNull { it.isDirectory } ?: error("исходники не найдены: проверка прошла бы впустую")

    private val ports = listOf(
        com.kert0n.medapp.feature.course.CourseReadings::class.java,
        com.kert0n.medapp.feature.medkits.MedKitReadings::class.java,
        com.kert0n.medapp.feature.notification.ReminderReadings::class.java,
        PackageRecords::class.java,
        com.kert0n.medapp.feature.packages.PackageReadings::class.java,
        CourseRecords::class.java,
        MedKitRecords::class.java,
        IntakeRecords::class.java,
        com.kert0n.medapp.feature.intake.IntakeReadings::class.java,
        com.kert0n.medapp.feature.intake.IntakeAccounts::class.java,
        ReportReadings::class.java,
        ReminderRecords::class.java
    )

    /**
     * Методы портов по имени — списком подписей: перегрузка даёт под одним именем две строки, и
     * договор с одной подписью её не пропустит.
     */
    private fun methods(): Map<String, List<String>> = ports.flatMap { port ->
        port.declaredMethods
            .filterNot { it.isSynthetic || it.isBridge }
            // Kotlin дописывает к имени хеш, когда аргумент — `value class` (`Revision`):
            // договор называет метод так, как он записан в исходнике.
            .map { "${port.simpleName}.${it.name.substringBefore('-')}" to signatureOf(it) }
    }.groupBy({ it.first }, { it.second })

    /**
     * `(параметры): возврат` простыми именами. У `suspend` на JVM последний параметр —
     * `Continuation<? super T>`, а возврат — `Object`: настоящий возврат читается из продолжения.
     */
    private fun signatureOf(method: Method): String {
        val params = method.genericParameterTypes.map { it.typeName }
        val suspending = params.lastOrNull()?.startsWith("kotlin.coroutines.Continuation") == true
        val arguments = if (suspending) params.dropLast(1) else params
        val returns = if (suspending) params.last().substringAfter("? super ").removeSuffix(">") else method.genericReturnType.typeName
        return "(${arguments.joinToString(", ") { simple(it) }}): ${simple(returns)}"
    }

    private fun simple(type: String): String = type.replace(Regex("""\b(?:[a-z_]\w*\.)+(?=[A-Za-z_])"""), "")

    @Test
    fun everyPortMethodNamesItsShapeAndSignature() {
        val found = methods()
        assertTrue("у портов не нашлось ни одного метода — читается не то", found.isNotEmpty())
        assertEquals("методы без договора: ${found.keys - contract.keys}", emptySet<String>(), found.keys - contract.keys)
        assertEquals("договор называет то, чего нет: ${contract.keys - found.keys}", emptySet<String>(), contract.keys - found.keys)
        val changed = found.filter { (name, signatures) -> signatures != listOf(contract.getValue(name).signature) }
        assertEquals(
            "подпись разошлась с договором (или появилась перегрузка) — форму нужно назвать заново: " +
                changed.map { (name, signatures) -> "$name: договор ${contract.getValue(name).signature}, в коде $signatures" },
            emptyMap<String, List<String>>(),
            changed
        )
    }

    /**
     * Форма `IN_TRANSACTION` держится не подписью, а вызывающими, поэтому проверяется трижды: у
     * каждого такого метода назван список владельцев, никто кроме них его не зовёт, и каждый
     * владелец действительно открывает транзакцию вокруг чтения и записи.
     */
    @Test
    fun onlyTheNamedStepsReadAndWriteInOneTransaction() {
        val guarded = contract.filterValues { it.shape == Shape.IN_TRANSACTION }.keys
        assertEquals("шаг-владелец не назван у метода формы IN_TRANSACTION", inTransaction.keys, guarded)

        for ((method, owners) in inTransaction) {
            val name = method.substringAfter('.')
            for (owner in owners) {
                val file = File(sources, owner)
                assertTrue("нет файла $owner — проверка сторожила бы пустоту", file.isFile && file.length() > 0)
                assertTrue(
                    "$owner назван владельцем $method, но не открывает транзакцию вокруг чтения и записи",
                    file.readText().contains("transactions.run")
                )
            }
            // Порт и его реализация метод объявляют — речь о тех, кто его **зовёт**.
            val callers = sources.walkTopDown()
                .filter { it.extension == "kt" }
                .filterNot { it.relativeTo(sources).invariantSeparatorsPath.startsWith("storage/") }
                .filter { it.readText().contains(Regex("\\.$name\\s*\\(")) }
                .map { it.relativeTo(sources).invariantSeparatorsPath }
                .toSortedSet()
            assertEquals("$method зовут не только названные шаги-владельцы", owners.toSortedSet(), callers)
        }
    }

    /**
     * Условная запись не молчит (PLAN C1): метод, отвечающий `Boolean` «записалось ли», помечен
     * `@CheckResult`, и проигнорированный ответ роняет lint, а не ждёт ревью. Аннотация живёт в
     * байткоде без времени выполнения, поэтому читается исходник порта.
     */
    @Test
    fun everyConditionalWriteDemandsItsResultBeRead() {
        val conditional = contract.filter { (_, clause) ->
            clause.shape in setOf(Shape.GUARDED, Shape.NAMED_FIELDS, Shape.ACTION) && clause.signature.endsWith(": Boolean")
        }.keys
        assertTrue("условных записей не нашлось — проверка сторожила бы пустоту", conditional.isNotEmpty())
        val unmarked = conditional.filterNot { method ->
            val (port, name) = method.split('.')
            val file = sources.walkTopDown().first { it.name == "$port.kt" }
            file.readText().contains(Regex("@CheckResult\\s+suspend fun $name\\("))
        }
        assertEquals("условная запись без @CheckResult", emptyList<String>(), unmarked)
    }

    @Test
    fun onlyTheNamedDebtTakesAReadEntityWithoutARevision() {
        val unguarded = contract.filterValues { it.shape == Shape.UNGUARDED }.keys
        assertEquals(
            "прочитанный экземпляр без редакции принимает метод, которого нет в списке долга",
            known,
            unguarded
        )
    }
}
