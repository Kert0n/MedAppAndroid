package com.kert0n.medapp.tour

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.feature.account.StoredAccount
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.medkits.MedKitInvitation
import com.kert0n.medapp.feature.medkits.MedKitPublishing
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.ProbeAccounts
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.snapshotStorage
import com.kert0n.medapp.network.delivery.MedAppCourier
import com.kert0n.medapp.network.delivery.MedAppDoor
import com.kert0n.medapp.network.delivery.MedAppPacking
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.medkit.ServerMedKitInvitations
import com.kert0n.medapp.network.pack.ClaimPostNetworkDTO
import com.kert0n.medapp.network.pack.PackagePatchNetworkDTO
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.register.MedAppRegister
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.network.value.VocabularySnapshotNetworkDTO
import com.kert0n.medapp.network.value.bundledVocabulary
import com.kert0n.medapp.platform.credentials.KeystoreCredentialSource
import com.kert0n.medapp.platform.credentials.KeystoreKey
import com.kert0n.medapp.queue.QueueWorker
import com.kert0n.medapp.queue.SnapshotApplier
import com.kert0n.medapp.storage.database.BundledVocabulary
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.RoomTransactions
import com.kert0n.medapp.storage.medkit.toStorageEntity
import com.kert0n.medapp.storage.value.VocabularyRoomRepository
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Стартовые условия показа — в настоящем приложении на телефоне** (`docs/demo/съёмка-с-телефона.md`).
 * Сюжет играет человек: камера живая, шторка настоящая, и всё, что видно на общей аптечке, тоже
 * настоящее. «Дача» заведена на боевом сервере, в ней трое, и соседи действуют своими клиентами:
 * бронь на Но-шпу и чужая правка Аспирина, из-за которой сервер отказал. Приём Смекты записан уже
 * без связи и едет к серверу, пока связь не вернут в кадре.
 *
 * Приложение на время показа живёт **пробной учёткой A**, соседи — учётками B и C (`local.properties`).
 * Живая учётка и живая база владельца не трогаются: перед первым посевом они откладываются в
 * `files/demo-backup`, а `-e stage restore` кладёт их обратно и убирает «Дачу» с сервера.
 *
 * Включается только явным `-e stage seed|restore`: он пишет **тот самый файл базы**, который
 * открывает приложение, и в обычном прогоне ему делать нечего.
 */
@RunWith(AndroidJUnit4::class)
class PhoneStage {

    private val arguments get() = InstrumentationRegistry.getArguments()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private val backup get() = File(context.filesDir, "demo-backup")
    private val complete get() = File(backup, "complete")

    /** Что лежит у приложения своего, кроме ключа в Keystore: база, учётка, настройки. */
    private fun ownFiles(): List<File> {
        val database = context.getDatabasePath(MedAppDatabase.NAME)
        return listOf(
            database,
            File(database.path + "-wal"),
            File(database.path + "-shm"),
            File(database.path + "-journal"),
            context.preferencesDataStoreFile("credentials"),
            context.preferencesDataStoreFile("settings")
        )
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    /**
     * Режим полёта у Pixel помнит Wi-Fi включённым, поэтому связь отнимается обоими выключателями
     * и возвращается обоими.
     */
    private fun online(on: Boolean) {
        shell("cmd connectivity airplane-mode ${if (on) "disable" else "enable"}")
        shell("cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}")
        if (on) Thread.sleep(5_000)
    }

    private fun credentials(): KeystoreCredentialSource {
        val file = context.preferencesDataStoreFile("credentials")
        return KeystoreCredentialSource(PreferenceDataStoreFactory.create { file }, file, KeystoreKey("medapp.account.key"), Dispatchers.IO)
    }

    /**
     * Кто «я» в мире показа. По умолчанию — пробная учётка A, и живая учётка устройства на время
     * показа откладывается. С `-e me own` — собственная учётка устройства: так два устройства
     * показывают каждое свою «Дачу» и не видят и не убирают чужую.
     */
    private val ownAccount: Boolean get() = arguments.getString("me") == "own"

    private suspend fun me(store: KeystoreCredentialSource): MedAppApi {
        if (!ownAccount) return requireNotNull(ProbeAccounts.anna) { "нет учётки A: -e probeBaseUrl, probeLoginA, probeKeyA" }
        val account = when (val stored = store.read()) {
            is StoredAccount.Present -> stored.credentials
            is StoredAccount.Pending -> stored.credentials
            else -> error("у устройства нет своей учётки: откройте приложение один раз при связи")
        }
        return requireNotNull(ProbeAccounts.api(account)) { "нет адреса сервера: -e probeBaseUrl" }
    }

    @Test
    fun seed() {
        assumeTrue("посев в настоящее приложение включается только -e stage seed", arguments.getString("stage") == "seed")
        val annaAccount = requireNotNull(ProbeAccounts.annaAccount) { "нет учётки A: -e probeBaseUrl, probeLoginA, probeKeyA" }
        val boris = requireNotNull(ProbeAccounts.boris) { "нет учётки B: -e probeLoginB, probeKeyB" }
        val viktor = requireNotNull(ProbeAccounts.viktor) { "нет учётки C: -e probeLoginC, probeKeyC" }

        runBlocking {
            // Связь нужна посеву: «Дача» заводится на сервере. Отнимается она в конце.
            online(true)
            keepOwnersData()
            // Уведомления прошлого дубля — не этого: шторка начинается пустой.
            context.getSystemService(NotificationManager::class.java).cancelAll()
            val credentials = credentials()
            val me = me(credentials)
            clearServer(me)

            // Учётка A — в тот же файл и тем же ключом Keystore, что у приложения: ключ при записи
            // берётся существующий, поэтому отложенная живая учётка открывается им же и потом.
            if (!ownAccount) {
                credentials.save(annaAccount)
                credentials.confirm()
            }

            context.deleteDatabase(MedAppDatabase.NAME)
            val database = Room.databaseBuilder(context, MedAppDatabase::class.java, MedAppDatabase.NAME)
                .addCallback(BundledVocabulary { bundledVocabulary(context.assets.open(VocabularySnapshotNetworkDTO.ASSET).use { it.readBytes().decodeToString() }) })
                .addMigrations(*MedAppDatabase.MIGRATIONS)
                .build()
            try {
                val world = World(database, me, boris, viktor, ZoneId.systemDefault())
                world.seed()
            } finally {
                database.close()
            }
            // Связь отнимается сразу: иначе приём Смекты доехал бы раньше, чем его покажут в кадре.
            online(false)
        }
    }

    @Test
    fun restore() {
        assumeTrue("возврат живых данных включается только -e stage restore", arguments.getString("stage") == "restore")
        check(complete.exists()) { "отложенных данных нет: посев на этом телефоне не делался или они уже возвращены" }
        runBlocking {
            online(true)
            if (ProbeAccounts.baseUrl != null) {
                clearServer(me(credentials()))
            } else {
                say("учёток проб нет — «Дача» на сервере осталась; её уберёт следующий посев или restore с учётками")
            }
            context.getSystemService(NotificationManager::class.java).cancelAll()
            for (file in ownFiles()) file.delete()
            for (file in ownFiles()) {
                val kept = File(backup, file.name)
                if (kept.exists()) kept.copyTo(file, overwrite = true)
            }
            // Возвращённое не стирается: отложенное переименовывается и остаётся рядом.
            backup.renameTo(File(context.filesDir, "demo-backup-restored-${System.currentTimeMillis()}"))
            say("живая база и учётка возвращены")
        }
    }

    /** Только уборка сервера: «Дача» и всё, что видят пробные учётки. */
    @Test
    fun clear() {
        assumeTrue("уборка сервера включается только -e stage clear", arguments.getString("stage") == "clear")
        runBlocking {
            online(true)
            clearServer(me(credentials()))
        }
    }

    /**
     * Живое откладывается **один раз**: следующий посев поверх посеянного не должен затереть
     * отложенное миром показа. Вернули — откладывается заново.
     */
    private fun keepOwnersData() {
        if (complete.exists()) {
            say("живые данные уже отложены ${complete.readText()} — не трогаю")
            return
        }
        backup.deleteRecursively()
        backup.mkdirs()
        for (file in ownFiles().filter { it.exists() }) file.copyTo(File(backup, file.name), overwrite = true)
        // Копия и снаружи — на случай, если телефон придётся чистить: `adb pull` её достанет.
        context.getExternalFilesDir(null)?.let { outside ->
            val copy = File(outside, "demo-backup").apply { deleteRecursively(); mkdirs() }
            for (file in ownFiles().filter { it.exists() }) file.copyTo(File(copy, file.name), overwrite = true)
        }
        complete.writeText(Instant.now().toString())
        say("живые данные отложены: ${backup.list()?.joinToString()}")
    }

    /**
     * Убирается только своё: всё, что видит «я», — синтетика прошлых посевов. «Дача» другого
     * устройства соседей тоже держит, поэтому соседи ничего не удаляют и не покидают. Отказ списка —
     * не «пусто»: сразу после режима полёта сеть поднимается не мгновенно, и молча принятый сбой
     * оставил бы «Дачу» на сервере. Ждём ответа, а без него — падаем.
     */
    private suspend fun clearServer(me: MedAppApi) {
        var listed = me.medKits()
        repeat(15) { if (listed !is ApiResult.Success) { Thread.sleep(2_000); listed = me.medKits() } }
        val seen = ((listed as? ApiResult.Success) ?: error("сервер не отдал список аптечек: $listed")).value.map { it.id }
        seen.forEach { id -> if (me.deleteMedKit(id) !is ApiResult.Success) me.leaveMedKit(id) }
        val left = seen.filter { id -> (me.medKit(id) as? ApiResult.Failure)?.failure != ApiFailure.NotFound }
        check(left.isEmpty()) { "на сервере остались мои аптечки: $left" }
        say("с сервера убрано аптечек: ${seen.size}")
    }

    /**
     * Мир показа: у человека три аптечки — домашняя, общая «Дача» на троих и пустой рюкзак, —
     * лечение с нехваткой, которое в кадре подключает дачную пачку, и напоминание, срок которого
     * уже наступил: оно приходит, как только приложение поднимется. Всё заводится сценариями приложения и его очередью, как это сделал бы человек.
     */
    private inner class World(
        private val database: MedAppDatabase,
        private val api: MedAppApi,
        private val boris: MedAppApi,
        private val viktor: MedAppApi,
        private val zone: ZoneId
    ) {
        private val clock = Clock.system(zone)
        private val now: Instant = clock.instant()
        private val today: LocalDate = LocalDate.now(zone)
        private val vocabulary = VocabularyResolver(VocabularyRoomRepository(database.vocabulary()), api)
        private val snapshots = PackageSnapshotResolver(vocabulary, database.queueStorage())
        private val reading = SnapshotApplier(MedAppRegister(api, snapshots, clock), database.snapshotStorage(), clock)
        private val worker = QueueWorker(database.queueStorage(), MedAppCourier(MedAppDoor(api), vocabulary, snapshots, clock), MedAppPacking(), RoomTransactions(database), clock)
        private val invitation = MedKitInvitation(database.medKitRepository(), ServerMedKitInvitations(api), reading, Duration.ofMinutes(60), clock)

        private lateinit var pieces: QuantityUnit
        private lateinit var millilitres: QuantityUnit
        private lateinit var tablets: DosageForm
        private lateinit var capsules: DosageForm
        private lateinit var syrup: DosageForm
        private lateinit var powder: DosageForm

        private val home = medKit(id = Uuid.random(), name = "Домашняя", location = "В ванной, верхняя полка", createdAt = now)
        private val dacha = medKit(id = Uuid.random(), name = "Дача", location = "Кухонный шкаф", createdAt = now)
        private val backpack = medKit(id = Uuid.random(), name = "Рюкзак", createdAt = now)

        private val nurofen = Uuid.random()
        private val cetrin = Uuid.random()
        private val express = Uuid.random()
        private val noShpa = Uuid.random()
        private val smecta = Uuid.random()
        private val aspirin = Uuid.random()

        private fun scenarios() = Scenarios(database, clock.instant(), zone)

        private fun pcs(amount: String) = Quantity(BigDecimal(amount), pieces)

        private fun <T> ok(result: ApiResult<T>, what: String): T = when (result) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> error("$what: $result")
        }

        private suspend fun refresh(what: String) {
            val outcome = reading.refresh()
            check(outcome is SnapshotApplier.Outcome.Applied && outcome.skipped.isEmpty()) { "$what: $outcome" }
        }

        private suspend fun drain(what: String) {
            val report = worker.drain()
            check(report.failed.isEmpty()) { "$what — сбои прохода: ${report.failed}" }
        }

        suspend fun seed() {
            ok(vocabulary.refresh(), "словарь сервера")
            val words = vocabulary.snapshot()
            pieces = requireNotNull(words.unitWithName("шт"))
            millilitres = requireNotNull(words.unitWithName("мл"))
            tablets = requireNotNull(words.formWithName("таблетки"))
            capsules = requireNotNull(words.formWithName("капсулы"))
            syrup = requireNotNull(words.formWithName("сироп"))
            powder = requireNotNull(words.formWithName("порошок"))

            for (kit in listOf(home, dacha, backpack)) database.medKits().upsert(kit.toStorageEntity())
            homeBoxes()
            dachaBoxes()
            shareDacha()
            courses()
            neighbours()
            scenarios().dailyRound.run()
            say("мир посеян; напоминание «Цетрин при аллергии» — на ${dueNow()}")
        }

        private suspend fun homeBoxes() {
            val boxes = database.packageRepository()
            boxes.add(
                pack(
                    id = nurofen, name = "Нурофен", medKit = home.ref, quantity = pcs("14"), form = tablets,
                    manufacturer = "Reckitt Benckiser", country = "Великобритания", category = "Обезболивающие",
                    expiresOn = ExpiryDate(today.plusMonths(14)), defaultIntakeAmount = Dose(pcs("1")),
                    note = "Пить после еды", price = Money(BigDecimal("320")), purchasedOn = today.minusDays(20), addedAt = now
                )
            )
            boxes.add(pack(id = Uuid.random(), name = "Парацетамол", medKit = home.ref, quantity = pcs("8"), form = tablets, category = "Жаропонижающие", expiresOn = ExpiryDate(today.minusDays(40)), addedAt = now))
            boxes.add(pack(id = Uuid.random(), name = "Ибупрофен", medKit = home.ref, quantity = pcs("16"), form = capsules, category = "Обезболивающие", expiresOn = ExpiryDate(today.plusDays(9)), addedAt = now))
            boxes.add(pack(id = cetrin, name = "Цетрин", medKit = home.ref, quantity = pcs("20"), form = tablets, category = "От аллергии", expiresOn = ExpiryDate(today.plusYears(2)), defaultIntakeAmount = Dose(pcs("1")), addedAt = now))
            boxes.add(pack(id = Uuid.random(), name = "Амброксол", medKit = home.ref, quantity = Quantity(BigDecimal("100"), millilitres), form = syrup, category = "От кашля", addedAt = now))
        }

        /** Коробки «Дачи» заводятся на местной полке и уезжают на сервер вместе с ней. */
        private suspend fun dachaBoxes() {
            val boxes = database.packageRepository()
            // Тот же Нурофен, но срок ближе: его в кадре подключают к лечению и ставят первым.
            boxes.add(
                pack(
                    id = express, name = "Нурофен Экспресс", medKit = dacha.ref, quantity = pcs("10"), form = tablets,
                    category = "Обезболивающие", expiresOn = ExpiryDate(today.plusMonths(2)), defaultIntakeAmount = Dose(pcs("1")), addedAt = now
                )
            )
            boxes.add(pack(id = noShpa, name = "Но-шпа", medKit = dacha.ref, quantity = pcs("24"), form = tablets, category = "Спазмолитики", expiresOn = ExpiryDate(today.plusYears(1)), addedAt = now))
            boxes.add(pack(id = smecta, name = "Смекта", medKit = dacha.ref, quantity = pcs("10"), form = powder, defaultIntakeAmount = Dose(pcs("1")), addedAt = now))
            boxes.add(pack(id = aspirin, name = "Аспирин", medKit = dacha.ref, quantity = pcs("20"), form = tablets, expiresOn = ExpiryDate(today.plusYears(1)), addedAt = now))
        }

        /** «Дача» становится общей и зовёт двоих: Бориса и Виктора. */
        private suspend fun shareDacha() {
            check(scenarios().medKitPublishing.publish(dacha.id) == MedKitPublishing.Outcome.PUBLISHING) { "«Дача» не публикуется" }
            drain("публикация «Дачи»")
            val published = requireNotNull(database.medKitRepository().find(dacha.id))
            check(published.publication == MedKit.Publication.PUBLISHED && published.acceptsInvitations) { "«Дача» не доехала: ${published.status}" }
            for ((who, neighbour) in listOf("Борис" to boris, "Виктор" to viktor)) {
                val outcome = invitation.invite(dacha.id)
                val key = (outcome as? MedKitInvitation.Outcome.Invited)?.invitation?.key ?: error("приглашение не выдано: $outcome")
                ok(neighbour.joinMedKit(MembershipPostNetworkDTO(key.value)), "$who не вошёл в «Дачу»")
            }
        }

        /** Время приёма, которое уже наступило: напоминание по нему приходит сразу. */
        private fun dueNow(): LocalTime = LocalTime.now(zone).truncatedTo(ChronoUnit.MINUTES)

        /** Время дня, не уходящее за полночь: пункт должен остаться сегодняшним. */
        private fun todayAt(shiftMinutes: Long): LocalTime {
            val at = LocalTime.now(zone).truncatedTo(ChronoUnit.MINUTES)
            val shifted = at.plusMinutes(shiftMinutes)
            return when {
                shiftMinutes < 0 && shifted.isAfter(at) -> LocalTime.of(0, 5)
                shiftMinutes > 0 && shifted.isBefore(at) -> LocalTime.of(23, 59)
                else -> shifted
            }
        }

        private suspend fun courses() {
            // Лечения назначены вчера с утра: расписание в прошлом завести нельзя, поэтому и
            // сценарии идут по вчерашним часам, как шли бы у человека.
            val scenarios = Scenarios(database, today.minusDays(1).atStartOfDay(zone).toInstant().plusSeconds(60), zone)
            // Идущее лечение со вчерашнего дня: три раза в день, двадцать один приём, а в домашней
            // пачке на него четырнадцать — нехватка. Вчера два приёма приняты, вечерний остался без
            // ответа: его и показывает попап «Без ответа за прошлые дни» при входе. Сегодня первый
            // приём уже принят.
            // Три разных времени в пределах суток: утреннее уже прошло, два впереди. Поздним вечером
            // «впереди» упирается в полночь — тогда два последних встают в 23:55 и 23:58.
            val times = listOf(todayAt(-120), todayAt(90).coerceAtMost(LocalTime.of(23, 55)), todayAt(180).coerceAtMost(LocalTime.of(23, 58)))
            val back = scenarios.courseDrafting.create("Нурофен от спины", "Назначил невролог")
            val backSaved = scenarios.courseDrafting.edit(
                back.id, back.revision,
                listOf(
                    CourseDrafting.Edit.SetDose(Dose(pcs("1"))),
                    CourseDrafting.Edit.SetForm(tablets),
                    CourseDrafting.Edit.SetSchedule(schedule(today.minusDays(1), *times.toTypedArray())),
                    CourseDrafting.Edit.SetTotalDoses(Doses(21)),
                    CourseDrafting.Edit.Attach(nurofen, Doses(14))
                )
            ) as CourseDrafting.Outcome.Saved
            scenarios.courseActivation.activate(backSaved.draft.id, backSaved.draft.revision)
            val slots = database.intakeRepository().ofCourse(backSaved.draft.id).filterIsInstance<CourseIntake>()
            val taken = listOf(today.minusDays(1) to times[0], today.minusDays(1) to times[1], today to times[0])
            for ((day, time) in taken) {
                val slot = slots.firstOrNull { it.slot.localDate == day && it.slot.at.atZone(zone).toLocalTime() == time }
                if (slot == null) {
                    say("приём Нурофена $day $time календарь не завёл")
                    continue
                }
                val at = slot.slot.at.plus(Duration.ofMinutes(7))
                Scenarios(database, at, zone).intakeConfirmation.confirm(slot.id, nurofen, Dose(pcs("1")), at)
            }

            // По нему придёт напоминание, и ответ на него дают из шторки. Начато вчера, и вчерашний
            // приём без ответа — вторая строка попапа.
            val allergy = scenarios.courseDrafting.create("Цетрин при аллергии", "Назначил терапевт")
            val allergySaved = scenarios.courseDrafting.edit(
                allergy.id, allergy.revision,
                listOf(
                    CourseDrafting.Edit.SetDose(Dose(pcs("1"))),
                    CourseDrafting.Edit.SetForm(tablets),
                    CourseDrafting.Edit.SetSchedule(schedule(today.minusDays(1), dueNow())),
                    CourseDrafting.Edit.SetTotalDoses(Doses(5)),
                    CourseDrafting.Edit.Attach(cetrin, Doses(5))
                )
            ) as CourseDrafting.Outcome.Saved
            scenarios.courseActivation.activate(allergySaved.draft.id, allergySaved.draft.revision)

            // Черновик: записано у врача, купить потом.
            scenarios().courseDrafting.create("Витамин D", "Купить в субботу, по одной капсуле утром")
        }

        /**
         * Соседи действуют своими клиентами, по той картине, которую прочитали: бронь Бориса телефон
         * читает снимком, отказ по Аспирину получает своим проходом очереди.
         */
        private suspend fun neighbours() {
            val noShpaSeen = ok(boris.packageSnapshot(noShpa), "Борис не видит Но-шпу").claims.version
            ok(boris.createClaim(ClaimPostNetworkDTO(noShpa, "6", noShpaSeen)), "Борис не заявил на Но-шпу")
            refresh("снимок после брони Бориса")

            // Я пересчитал Аспирин, а Виктор тем временем забрал почти всю пачку: разница уводит
            // остаток ниже нуля, и сервер отказывает.
            val adjusted = scenarios().packageAdjusting.adjust(aspirin, PackageAdjusting.Action.Recount(seen = pcs("20"), actual = pcs("18")))
            check(adjusted == PackageAdjusting.Outcome.ADJUSTED) { "пересчёт Аспирина: $adjusted" }
            val aspirinSeen = ok(viktor.packageSnapshot(aspirin), "Виктор не видит Аспирин").pack.version
            ok(viktor.patchPackage(aspirin, PackagePatchNetworkDTO(amount = "1", version = aspirinSeen)), "Виктор не изменил Аспирин")
            drain("отказ по Аспирину")
            refresh("снимок после отказа")

            // В пути: приём Смекты без связи. Проход очереди его больше не увидит — до кадра.
            scenarios().unplannedIntakeRecording.record(smecta, Dose(pcs("1")), clock.instant(), acknowledged = true)

            // Только по `-e neighbour true`: Борис взял из дачной пачки, и телефон узнает об этом,
            // когда в кадре вернут связь, — уведомлением «Лекарства стало меньше».
            if (arguments.getString("neighbour") == "true") {
                val expressSeen = ok(boris.packageSnapshot(express), "Борис не видит Нурофен Экспресс").pack.version
                ok(boris.patchPackage(express, PackagePatchNetworkDTO(amount = "4", version = expressSeen)), "Борис не изменил Нурофен Экспресс")
            }

        }

        private fun schedule(start: LocalDate, vararg times: LocalTime) =
            com.kert0n.medapp.fixture.schedule(start = start, daysOfWeek = DayOfWeek.entries.toSet(), times = times.distinct().sorted(), zone = zone)
    }

    private companion object {
        const val TAG = "PhoneStage"
    }
}
