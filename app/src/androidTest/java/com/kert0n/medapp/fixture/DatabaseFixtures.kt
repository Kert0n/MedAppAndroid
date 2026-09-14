package com.kert0n.medapp.fixture

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.pack.toDetailsStorageEntity as toPackageDetailsStorageEntity
import com.kert0n.medapp.storage.pack.toStorageEntity as toPackageStorageEntity
import com.kert0n.medapp.storage.pack.toStorageEntity as toRecordStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import kotlinx.coroutines.runBlocking

/**
 * База для проверки в памяти: прогон не оставляет файла и не зависит от прошлого прогона.
 * Ограничения внешних ключей включены явно — без них `RESTRICT` не проверяется вовсе.
 *
 * Словарь и две аптечки фикстур засеяны: строки держат идентификаторы единиц, форм и аптечек, а
 * собираются в домен по словарю и связям, и без них ни одна пачка из базы не читается.
 */
fun inMemoryDatabase(observeQueries: ((String) -> Unit)? = null): MedAppDatabase {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val builder = Room.inMemoryDatabaseBuilder(context, MedAppDatabase::class.java)
    // Перехват запросов нужен там, где проверяется не только ответ, но и сколько его стоило.
    // Исполнитель прямой: с отдельным потоком уведомление о запросе могло не дойти к моменту,
    // когда тест считает прочитанное, и счёт вышел бы меньше настоящего — то есть тихо зелёным.
    observeQueries?.let { observe ->
        builder.setQueryCallback({ sql, _ -> observe(sql) }, { command -> command.run() })
    }
    return builder.build().seeded()
}

private fun MedAppDatabase.seeded(): MedAppDatabase = apply {
    runBlocking {
        vocabulary().save(
            units = listOf(TABLETS, MILLILITRES).map { it.toStorageEntity() },
            forms = listOf(TABLET_FORM, CAPSULE_FORM).map { it.toStorageEntity() }
        )
        medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
        medKits().insertIfMissing(medKit(id = SHARED_KIT, name = "Дача").toMedKitStorageEntity())
    }
}

/**
 * База в файле: нужна там, где проверяется, что записанное переживает закрытие соединения —
 * в памяти это не проверить по определению.
 */
fun fileDatabase(name: String): MedAppDatabase {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.deleteDatabase(name)
    return Room.databaseBuilder(context, MedAppDatabase::class.java, name).build().seeded()
}

fun reopenFileDatabase(name: String): MedAppDatabase {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return Room.databaseBuilder(context, MedAppDatabase::class.java, name).build()
}

/**
 * Запись, которую схема запрещает: возвращает отказ базы, чтобы тест утверждал именно про него.
 *
 * Вложенный `runTest` внутри `runTest` не запускается, поэтому ожидание отказа выражается
 * перехватом, а не `assertThrows` вокруг второго построителя.
 */
suspend fun rejectedByDatabase(block: suspend () -> Unit): Throwable =
    runCatching { block() }.exceptionOrNull()
        ?: throw AssertionError("база приняла запись, которую схема запрещает")

/**
 * Репозитории поверх открытой базы. Транзакции F5 идут через несколько таблиц, поэтому у их
 * владельцев несколько DAO; собирать их в каждом тесте заново значило бы повторять граф руками.
 */
fun MedAppDatabase.packageRepository() = com.kert0n.medapp.storage.pack.PackageRoomRepository(
    this, packages(), courses(), syncOperations(), intakes(), vocabulary()
)

fun MedAppDatabase.courseRepository() = com.kert0n.medapp.storage.course.CourseRoomRepository(
    this, courses(), intakes(), packages(), syncOperations(), vocabulary()
)

fun MedAppDatabase.intakeRepository() = com.kert0n.medapp.storage.intake.IntakeRoomRepository(
    this, intakes(), packages(), courses(), vocabulary()
)

fun MedAppDatabase.medKitRepository() = com.kert0n.medapp.storage.medkit.MedKitRoomRepository(
    this, medKits()
)

fun MedAppDatabase.queueRepository() = com.kert0n.medapp.storage.server.SyncOperationRoomRepository(
    this, syncOperations(), vocabulary()
)

/** Отчёты личного кабинета — одним снимком базы (PLAN H6). */
fun MedAppDatabase.reportRepository() = com.kert0n.medapp.storage.report.ReportRoomRepository(
    this, intakes(), packages(), courses(), vocabulary()
)

/** «Одна транзакция» — узкий порт поверх той же базы (PLAN F5). */
fun MedAppDatabase.transactions() = com.kert0n.medapp.storage.database.RoomTransactions(this)

/** Порт очереди для работника — транзакции взятия и применения исхода. */
fun MedAppDatabase.queueStorage() = com.kert0n.medapp.storage.server.QueueRoomStorage(
    this, syncOperations(), packages(), intakes(), medKits(), courses(), vocabulary()
)

/** Порт полного снимка — укладка целиком одной транзакцией; полка с сервера зовётся как в ресурсах. */
fun MedAppDatabase.snapshotStorage() = com.kert0n.medapp.storage.server.SnapshotRoomStorage(
    this, medKits(), packages(), courses(), intakes(), vocabulary(), syncOperations(),
    arrivedName = "Общая аптечка"
)

/**
 * Пачка целиком в базу: запись о коробке, живая строка и сведения — как их пишет репозиторий.
 * Тестам DAO не нужно повторять сборку трёх строк, чтобы положить одну пачку.
 */
suspend fun com.kert0n.medapp.storage.pack.PackageDao.save(
    pkg: com.kert0n.medapp.domain.pack.Package,
    sync: com.kert0n.medapp.network.pack.PackageSyncState = com.kert0n.medapp.network.pack.PackageSyncState(pkg.id)
) = save(
    pkg.record.toRecordStorageEntity(),
    pkg.toPackageStorageEntity(sync),
    pkg.toPackageDetailsStorageEntity()
)

/** Служба очереди поверх той же базы: пара «изменение и команда» одной транзакцией. */
fun MedAppDatabase.queueService() = com.kert0n.medapp.queue.QueueService(transactions(), queueStorage())

/**
 * Сценарии над одной базой с остановленными часами [now]: удаление и перенос коробки, уборка
 * полки. Собираются вместе, потому что аптечка зовёт шаги коробки, и граф один.
 */
class Scenarios(
    database: MedAppDatabase,
    now: java.time.Instant,
    val notifier: FakeNotifier = FakeNotifier(),
    val reminders: FakeReminders = FakeReminders(),
    val notificationSettings: FakeSettings = FakeSettings()
) {
    private val clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)
    val reminderWithdrawal = com.kert0n.medapp.feature.notification.ReminderWithdrawal(notifier, reminders)
    private val packages = database.packageRepository()
    private val medKits = database.medKitRepository()
    private val courses = database.courseRepository()
    private val queue = database.queueService()
    private val transactions = database.transactions()

    val packageAdding = com.kert0n.medapp.feature.packages.PackageAdding(packages, medKits, queue, transactions, clock)
    val courseCalendar = com.kert0n.medapp.feature.course.CourseCalendar(database.intakeRepository(), packages)
    val courseClamping = com.kert0n.medapp.feature.course.CourseClamping(courses, packages, courseCalendar, queue)
    val packageDescribing = com.kert0n.medapp.feature.packages.PackageDescribing(packages, courseClamping, queue, transactions, clock)
    val packageRemoval = com.kert0n.medapp.feature.packages.PackageRemoval(
        packages, queue, transactions, clock
    )
    val packageRelocation = com.kert0n.medapp.feature.packages.PackageRelocation(
        packages, medKits, courses, queue, transactions, clock
    )
    val medKitKeeping = com.kert0n.medapp.feature.medkits.MedKitKeeping(medKits, transactions, clock)
    val medKitRemoval = com.kert0n.medapp.feature.medkits.MedKitRemoval(
        medKits, packages, packageRemoval, packageRelocation, queue, transactions, clock
    )
    val medKitPublishing = com.kert0n.medapp.feature.medkits.MedKitPublishing(
        medKits, packages, packageRelocation, queue, transactions, clock
    )
    val courseDrafting = com.kert0n.medapp.feature.course.CourseDrafting(courses, packages, transactions, clock)
    val packageAdjusting = com.kert0n.medapp.feature.packages.PackageAdjusting(packages, courseClamping, queue, transactions, clock)
    val unplannedIntakeRecording = com.kert0n.medapp.feature.intake.UnplannedIntakeRecording(
        database.intakeRepository(), courses, packages, courseClamping, queue, transactions, clock
    )
    val courseUpkeep = com.kert0n.medapp.feature.course.CourseUpkeep(courses, courseCalendar, transactions, clock)
    val courseActivation = com.kert0n.medapp.feature.course.CourseActivation(
        courses, packages, courseCalendar, queue, transactions, clock
    )
    val courseClosing = com.kert0n.medapp.feature.course.CourseClosing(courses, packages, queue)
    val courseCancellation = com.kert0n.medapp.feature.course.CourseCancellation(
        courses, database.intakeRepository(), courseCalendar, courseClosing, reminderWithdrawal, transactions, clock
    )
    val courseAmendment = com.kert0n.medapp.feature.course.CourseAmendment(
        courses, database.intakeRepository(), packages, courseCalendar, courseClosing, queue, reminderWithdrawal, transactions, clock
    )
    val sourceEditing = com.kert0n.medapp.feature.course.SourceEditing(
        courses, database.intakeRepository(), packages, courseCalendar, queue, transactions, clock
    )
    val intakeDeclining = com.kert0n.medapp.feature.intake.IntakeDeclining(
        database.intakeRepository(), courses, courseCalendar, reminderWithdrawal, transactions, clock
    )
    val courseOffPlanCounting = com.kert0n.medapp.feature.course.CourseOffPlanCounting(
        courses, database.intakeRepository(), courseCalendar, courseClamping, courseClosing, reminderWithdrawal, transactions, clock
    )
    val intakeConfirmation = com.kert0n.medapp.feature.intake.IntakeConfirmation(
        database.intakeRepository(), courses, packages, transactions, queue, courseClosing, courseCalendar, reminderWithdrawal, clock
    )
    val reminderStore = com.kert0n.medapp.storage.notification.ReminderRoomRepository(database, database.reminders())
    val notificationPlanning = com.kert0n.medapp.feature.notification.NotificationPlanning(
        database.intakeRepository(), packages, courses, notificationSettings
    )
    val notificationDelivery = com.kert0n.medapp.feature.notification.NotificationDelivery(notifier, reminders, reminderStore, clock)
    val dailyRound = com.kert0n.medapp.feature.notification.DailyRound(courseUpkeep, notificationPlanning, notificationDelivery, clock)
    val reminderAnswering = com.kert0n.medapp.feature.notification.ReminderAnswering(
        database.intakeRepository(), intakeConfirmation, intakeDeclining, reminders, reminderStore, notificationSettings, clock
    )
}

/**
 * Записанный приём по пункту курса. Тест, который его ждёт, говорит об этом прямо, а не
 * разворачивает исход молча: вопрос и отказ здесь — провал с названным исходом.
 */
fun com.kert0n.medapp.feature.intake.IntakeConfirmation.Outcome.confirmed(): com.kert0n.medapp.feature.intake.IntakeConfirmation.Outcome.Confirmed =
    this as? com.kert0n.medapp.feature.intake.IntakeConfirmation.Outcome.Confirmed ?: error("ожидался записанный приём, а не $this")

/** Отказ сценария приёма по пункту курса — его причина. */
fun com.kert0n.medapp.feature.intake.IntakeConfirmation.Outcome.rejected(): com.kert0n.medapp.domain.intake.IntakeRejected.Reason =
    (this as? com.kert0n.medapp.feature.intake.IntakeConfirmation.Outcome.Rejected)?.reason ?: error("ожидался отказ, а не $this")
