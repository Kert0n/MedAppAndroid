package com.kert0n.medapp.tour

import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.settle
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid
import org.json.JSONObject

/**
 * Мир для снимков экранов: у человека три аптечки — домашняя, общая дача и пустой рюкзак, — в них
 * коробки во всех состояниях сразу (просроченная, истекающая, на лечении, обычная, в пути, с чужими
 * бронями, отвергнутая сервером), идущее лечение с нехваткой, черновик и отменённое лечение, и
 * день, на который уже отвечали.
 *
 * Всё заводится сценариями и переходами строк, как в историях (`docs/истории.md`): состояния,
 * которого приложение не выражает, мир не ставит. Словарь — **боевой**, из встроенного снимка:
 * имена единиц сервера («шт», «мл») на экране не склоняются, и документация показывает их такими,
 * какие они у людей.
 */
class TourWorld(private val database: MedAppDatabase, val zone: ZoneId = ZoneId.systemDefault()) {

    val now: Instant = Instant.now()
    val today: LocalDate = LocalDate.now(zone)

    lateinit var pieces: QuantityUnit
    lateinit var millilitres: QuantityUnit
    lateinit var tabletsForm: DosageForm
    lateinit var capsulesForm: DosageForm
    lateinit var syrupForm: DosageForm
    lateinit var powderForm: DosageForm

    val home = medKit(id = Uuid.random(), name = "Домашняя", location = "В ванной, верхняя полка")
    val dacha = medKit(id = Uuid.random(), name = "Дача", location = "Кухонный шкаф", publication = MedKit.Publication.PUBLISHED, participantCount = 3)
    val backpack = medKit(id = Uuid.random(), name = "Рюкзак")

    val nurofen = Uuid.random()
    val paracetamol = Uuid.random()
    val ibuprofen = Uuid.random()
    val cetrin = Uuid.random()
    val syrup = Uuid.random()
    val noShpa = Uuid.random()
    val smecta = Uuid.random()
    val amoxiclav = Uuid.random()
    val aspirin = Uuid.random()

    lateinit var backCourse: Uuid
    lateinit var draftCourse: Uuid
    lateinit var cancelledCourse: Uuid

    private fun scenarios() = Scenarios(database, now, zone)

    private fun pcs(amount: String) = Quantity(BigDecimal(amount), pieces)

    suspend fun seed() {
        vocabulary()
        database.medKits().upsert(home.toStorageEntity())
        database.medKits().upsert(dacha.toStorageEntity())
        database.medKits().upsert(backpack.toStorageEntity())
        homeBoxes()
        dachaBoxes()
        courses()
        answers()
        inFlight()
    }

    /** Словарь — тот же, что приложение везёт с собой: единицы и формы боевого сервера. */
    private suspend fun vocabulary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = JSONObject(context.assets.open("vocabulary.json").bufferedReader().use { it.readText() })
        val units = json.getJSONArray("quantityUnits").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .map { QuantityUnit(Uuid.parse(it.getString("id")), it.getString("name")) }
        val forms = json.getJSONArray("formTypes").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .map { DosageForm(Uuid.parse(it.getString("id")), it.getString("name")) }
        database.vocabulary().save(units = units.map { it.toStorageEntity() }, forms = forms.map { it.toStorageEntity() })
        pieces = units.first { it.name == "шт" }
        millilitres = units.first { it.name == "мл" }
        tabletsForm = forms.first { it.name == "таблетки" }
        capsulesForm = forms.first { it.name == "капсулы" }
        syrupForm = forms.first { it.name == "сироп" }
        powderForm = forms.first { it.name == "порошок" }
    }

    private suspend fun homeBoxes() {
        val boxes = database.packageRepository()
        boxes.add(
            pack(
                id = nurofen, name = "Нурофен", medKit = home.ref, quantity = pcs("12"), form = tabletsForm,
                manufacturer = "Reckitt Benckiser", country = "Великобритания", category = "Обезболивающие",
                expiresOn = ExpiryDate(today.plusMonths(14)), defaultIntakeAmount = Dose(pcs("1")),
                note = "Пить после еды", price = Money(BigDecimal("320")), purchasedOn = today.minusDays(20), addedAt = now
            )
        )
        boxes.add(pack(id = paracetamol, name = "Парацетамол", medKit = home.ref, quantity = pcs("8"), form = tabletsForm, category = "Жаропонижающие", expiresOn = ExpiryDate(today.minusDays(40)), addedAt = now))
        boxes.add(pack(id = ibuprofen, name = "Ибупрофен", medKit = home.ref, quantity = pcs("16"), form = capsulesForm, category = "Обезболивающие", expiresOn = ExpiryDate(today.plusDays(2)), addedAt = now))
        boxes.add(pack(id = cetrin, name = "Цетрин", medKit = home.ref, quantity = pcs("20"), form = tabletsForm, category = "От аллергии", expiresOn = ExpiryDate(today.plusYears(2)), addedAt = now))
        boxes.add(pack(id = syrup, name = "Амброксол", medKit = home.ref, quantity = Quantity(BigDecimal("100"), millilitres), form = syrupForm, category = "От кашля", addedAt = now))
    }

    /** Общая полка: коробки пришли с сервера и знают свои версии; на Но-шпу заявили другие. */
    private suspend fun dachaBoxes() {
        val boxes = database.packageRepository()
        fun synced(id: Uuid) = PackageSyncState(id, ResourceVersion(3), ResourceVersion(2), syncedAt = now)
        boxes.add(pack(id = noShpa, name = "Но-шпа", medKit = dacha.ref, quantity = pcs("24"), form = tabletsForm, expiresOn = ExpiryDate(today.plusYears(1)), claims = Claims(BigDecimal("6")), addedAt = now), synced(noShpa))
        boxes.add(pack(id = smecta, name = "Смекта", medKit = dacha.ref, quantity = pcs("10"), form = powderForm, addedAt = now), synced(smecta))
        boxes.add(pack(id = amoxiclav, name = "Амоксиклав", medKit = dacha.ref, quantity = pcs("14"), form = tabletsForm, expiresOn = ExpiryDate(today.plusMonths(8)), addedAt = now), synced(amoxiclav))
        boxes.add(pack(id = aspirin, name = "Аспирин", medKit = dacha.ref, quantity = pcs("20"), form = tabletsForm, expiresOn = ExpiryDate(today.plusYears(1)), addedAt = now), synced(aspirin))
        // Брони других участников хранятся своей дверью: у коробки их картина читается отдельно (PLAN D4).
        boxes.saveClaims(noShpa, Claims(BigDecimal("6")))
    }

    private suspend fun courses() {
        val scenarios = scenarios()
        // Идущее лечение: три раза в день, двадцать один приём, а в коробке двенадцать — нехватка.
        val back = scenarios.courseDrafting.create("Нурофен от спины", "Назначил невролог")
        val backSaved = scenarios.courseDrafting.edit(
            back.id, back.revision,
            listOf(
                CourseDrafting.Edit.SetDose(Dose(pcs("1"))),
                CourseDrafting.Edit.SetForm(tabletsForm),
                CourseDrafting.Edit.SetSchedule(schedule(today.minusDays(1), LocalTime.of(9, 0), LocalTime.of(14, 0), LocalTime.of(21, 0))),
                CourseDrafting.Edit.SetTotalDoses(Doses(21)),
                CourseDrafting.Edit.Attach(nurofen, Doses(12))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(backSaved.draft.id, backSaved.draft.revision)
        backCourse = backSaved.draft.id

        // Черновик: записано у врача, купить потом.
        draftCourse = scenarios.courseDrafting.create("Витамин D", "Купить в субботу, по одной капсуле утром").id

        // Отменённое лечение: история остаётся.
        val allergy = scenarios.courseDrafting.create("Цетрин при аллергии")
        val allergySaved = scenarios.courseDrafting.edit(
            allergy.id, allergy.revision,
            listOf(
                CourseDrafting.Edit.SetDose(Dose(pcs("1"))),
                CourseDrafting.Edit.SetForm(tabletsForm),
                CourseDrafting.Edit.SetSchedule(schedule(today.minusDays(3), LocalTime.of(20, 0))),
                CourseDrafting.Edit.SetTotalDoses(Doses(5)),
                CourseDrafting.Edit.Attach(cetrin, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(allergySaved.draft.id, allergySaved.draft.revision)
        cancelledCourse = allergySaved.draft.id
        scenarios.courseCancellation.cancel(cancelledCourse)
    }

    /** Сегодня утром приём принят, вчера вечером пропущен, днём — разовый Цетрин. */
    private suspend fun answers() {
        val scenarios = scenarios()
        val intakes = database.intakeRepository().ofCourse(backCourse)
            .filterIsInstance<com.kert0n.medapp.domain.intake.CourseIntake>()
        val morning = intakes.first { it.slot.localDate == today && it.slot.at.atZone(zone).hour == 9 }
        scenarios.intakeConfirmation.confirm(morning.id, nurofen, Dose(pcs("1")), today.atTime(9, 7).atZone(zone).toInstant())
        intakes.firstOrNull { it.slot.localDate == today.minusDays(1) && it.slot.at.atZone(zone).hour == 21 }
            ?.let { scenarios.intakeDeclining.decline(it.id, today.minusDays(1).atTime(21, 30).atZone(zone).toInstant()) }
        scenarios.unplannedIntakeRecording.record(cetrin, Dose(pcs("1")), today.atTime(8, 15).atZone(zone).toInstant())
    }

    /**
     * Решения, которые ещё едут серверу, и одно отвергнутое: без связи очередь стоит, и видно всё
     * сразу — «изменение ещё не доехало», «удаление в пути», отказ по остатку.
     */
    private suspend fun inFlight() {
        val scenarios = scenarios()
        scenarios.unplannedIntakeRecording.record(smecta, Dose(pcs("1")), now, acknowledged = true)
        scenarios.packageRemoval.remove(amoxiclav)
        val refused = Uuid.random()
        database.syncOperations().enqueue(refused, PackageSyncCommand.Consume(aspirin, Dose(pcs("5")), Uuid.random()), now)
        database.syncOperations().settle(refused, SyncOperationStatus.REFUSED, at = now, refusalReason = RefusalReason.INSUFFICIENT)
    }

    private fun schedule(start: LocalDate, vararg times: LocalTime) =
        com.kert0n.medapp.fixture.schedule(start = start, daysOfWeek = DayOfWeek.entries.toSet(), times = times.toList(), zone = zone)

    /** Коробка, у которой срок кончается сегодня, и проход дня, который об этом скажет при входе. */
    suspend fun expiringToday() {
        database.packageRepository().add(
            pack(id = Uuid.random(), name = "Називин", medKit = home.ref, quantity = Quantity(BigDecimal("10"), millilitres), expiresOn = ExpiryDate(today), addedAt = now)
        )
        scenarios().dailyRound.run()
    }
}
