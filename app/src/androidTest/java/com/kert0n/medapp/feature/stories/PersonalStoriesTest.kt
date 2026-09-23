package com.kert0n.medapp.feature.stories

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.feature.intake.IntakeWarning
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Money
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.intake.UnplannedIntakeRecording
import com.kert0n.medapp.feature.packages.PackageAdding
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.Mechanisms
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.confirmed
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueRepository
import com.kert0n.medapp.fixture.reportRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.pack.PackageQuery
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сквозные истории личного учёта без экранов (PLAN J2.1, J2.5, J2.7, J2.10): каждая — одним
 * тестом, шагами сценариев Base и утверждениями на **чтениях экранов** (U2), а не на DAO. Так
 * видно, что экрану есть что позвать и что показать — и что сценарии складываются в историю, а не
 * только работают порознь.
 */
@RunWith(AndroidJUnit4::class)
class PersonalStoriesTest {

    private lateinit var database: MedAppDatabase
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z") // 15:00 МСК 10 марта
    private val today: LocalDate = LocalDate.of(2027, 3, 10)

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() = database.close()

    private fun facts(name: String, expiresOn: LocalDate? = null, price: String? = null, form: Boolean = true) = PackageFacts(
        PackageSharedFacts(name, form = TABLET_FORM.takeIf { form }),
        expiresOn = expiresOn?.let { ExpiryDate(it) },
        price = price?.let { Money(BigDecimal(it)) }
    )

    private suspend fun added(scenarios: Scenarios, medKit: Uuid, name: String, quantity: String, expiresOn: LocalDate? = null, price: String? = null): Uuid =
        (scenarios.packageAdding.add(medKit, facts(name, expiresOn, price), tablets(quantity)) as PackageAdding.Outcome.Added).packageId

    /** Лечение по две таблетки раз в день с [start], [doses] доз из названных коробок. */
    private suspend fun treated(scenarios: Scenarios, title: String, start: LocalDate, doses: Int, sources: List<Pair<Uuid, Int>>): Uuid {
        val created = scenarios.courseDrafting.create(title)
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = start)),
                CourseDrafting.Edit.SetTotalDoses(Doses(doses))
            ) + sources.map { (id, n) -> CourseDrafting.Edit.Attach(id, Doses(n)) }
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    /**
     * **J2.1 Личный учёт без сети.** Аптечка → две пачки → пересчёт → приём → история → поиск и
     * фильтр. Ни одной команды в очереди: полка местная, и серверу везти нечего.
     */
    @Test
    fun personalAccountingWithoutTheNetwork() = runTest {
        val scenarios = Scenarios(database, now)
        val shelf = scenarios.medKitKeeping.create("Ванная", "полка над раковиной").id
        val paracetamol = added(scenarios, shelf, "Парацетамол", "20", price = "120")
        val ibuprofen = added(scenarios, shelf, "Ибупрофен", "10")

        assertEquals(PackageAdjusting.Outcome.ADJUSTED, scenarios.packageAdjusting.adjust(paracetamol, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("15"))))
        val recorded = scenarios.unplannedIntakeRecording.record(paracetamol, dose("2"), now)
        assertTrue("приём не записан: $recorded", recorded is UnplannedIntakeRecording.Outcome.Recorded)

        // Экран 6: карточка коробки — остаток после пересчёта и приёма.
        val card = requireNotNull(database.packageRepository().observe(paracetamol).first())
        assertEquals(tablets("13"), card.quantity)
        assertEquals(now, card.lastUsedAt)
        // Экран 19: история коробки — один разовый приём на две таблетки.
        val history = database.intakeRepository().observeOfPackage(paracetamol).first()
        assertEquals(listOf(dose("2")), history.map { it.taken?.amount })
        // Экран 5: поиск по имени и сортировка; экран 4: содержимое полки.
        val found = database.packageRepository().list(PackageQuery(text = "пара"), today).first()
        assertEquals(listOf(paracetamol), found.map { it.id })
        val byName = database.packageRepository().list(PackageQuery(medKitId = shelf, sort = PackageQuery.Sort.NAME), today).first()
        assertEquals(listOf(ibuprofen, paracetamol), byName.map { it.id })
        val free = database.packageRepository().list(PackageQuery(medKitId = shelf, filter = PackageQuery.Filter.HasFree), today).first()
        assertEquals(setOf(ibuprofen, paracetamol), free.map { it.id }.toSet())
        // Экран 2: список полок с содержимым.
        val shelves = database.medKitRepository().observeAll(today).first()
        assertEquals(2, requireNotNull(shelves.first { it.id == shelf }).contents.packages)
        // Экран 28: очередь пуста — полка местная.
        assertTrue(database.queueRepository().observeOutstanding().first().isEmpty())
    }

    /**
     * **J2.5 Просрочка.** Пачка с прошедшей датой: первой в списке при любой сортировке, помечена,
     * приём из неё спрашивает, а с подтверждением пишется, и сама она не удаляется — назавтра она
     * всё ещё на месте.
     */
    @Test
    fun anExpiredPackageIsFirstMarkedAsksAndStays() = runTest {
        val scenarios = Scenarios(database, now)
        val fresh = added(scenarios, HOME_KIT, "Аспирин", "10")
        val expired = added(scenarios, HOME_KIT, "Цитрамон", "10", expiresOn = today.minusDays(1))

        for (sort in PackageQuery.Sort.entries) {
            val listed = database.packageRepository().list(PackageQuery(medKitId = HOME_KIT, sort = sort), today).first()
            assertEquals("сортировка $sort: просроченная не первая", expired, listed.first().id)
        }
        val marked = database.packageRepository().list(PackageQuery(medKitId = HOME_KIT, filter = PackageQuery.Filter.Expired), today).first()
        assertEquals(listOf(expired), marked.map { it.id })
        assertTrue(requireNotNull(database.packageRepository().observe(expired).first()).facts.expiresOn!!.isExpiredOn(today))

        // Принять из неё можно, но не молча: приём спрашивает, решает человек (ТЗ 4.1.1.5.5,
        // решение владельца 2026-09-23).
        assertTrue(scenarios.unplannedIntakeRecording.record(expired, dose("1"), now) is UnplannedIntakeRecording.Outcome.Warned)
        assertTrue(scenarios.unplannedIntakeRecording.record(expired, dose("1"), now, acknowledged = true) is UnplannedIntakeRecording.Outcome.Recorded)

        val tomorrow = Scenarios(database, now.plusSeconds(86_400))
        tomorrow.dailyRound.run()
        assertEquals(setOf(fresh, expired), database.packageRepository().list(PackageQuery(medKitId = HOME_KIT), today.plusDays(1)).first().map { it.id }.toSet())
    }

    /**
     * **J2.7 Аналитика.** Истраченное за период — только мои приёмы: по эпизодам и разовые по
     * коробке; расход на три месяца — по идущему курсу; сводка — цена по пачкам с ценой и число
     * без цены отдельно. Пересчёт и утилизация в расход не попадают.
     */
    @Test
    fun analyticsCountOnlyMyIntakes() = runTest {
        val scenarios = Scenarios(database, now)
        val priced = added(scenarios, HOME_KIT, "Парацетамол", "40", price = "150")
        val unpriced = added(scenarios, HOME_KIT, "Ибупрофен", "30")
        val course = treated(scenarios, "Парацетамол курсом", today, 4, listOf(priced to 4))
        val first = database.intakeRepository().ofCourse(course).filterIsInstance<com.kert0n.medapp.domain.intake.CourseIntake>().minBy { it.plannedAt }
        scenarios.intakeConfirmation.confirm(first.id, priced, dose("2"), now).confirmed()
        scenarios.unplannedIntakeRecording.record(unpriced, dose("3"), now)
        // Не расход: пересчёт и утилизация.
        scenarios.packageAdjusting.adjust(unpriced, PackageAdjusting.Action.Recount(seen = tablets("27"), actual = tablets("20")))
        scenarios.packageAdjusting.adjust(priced, PackageAdjusting.Action.Dispose(seen = tablets("38"), amount = tablets("8")))

        val reports = database.reportRepository()
        val spending = reports.observeSpending(SpendingPeriod(today.minusDays(7), today), ZoneOffset.UTC).first()
        assertEquals(listOf(tablets("2")), spending.episodes.map { it.total })
        assertEquals(listOf(unpriced to tablets("3")), spending.packages.map { it.packageId to it.total })
        val future = reports.observeFutureSpending(SpendingHorizon(today, today.plusMonths(3))).first()
        assertEquals(listOf(Doses(3) to tablets("6")), future.episodes.map { it.doses to it.total })
        val summary = reports.observeStockSummary().first()
        assertEquals(2, summary.packages)
        assertEquals(listOf(Money(BigDecimal("150"))), summary.prices)
        assertEquals(1, summary.unpriced)
    }

    /**
     * **J2.10 Календарь годности.** Источник курса получает предупреждения за 3 дня и за день;
     * обычная пачка — нет; в последний день обеим — баннер в приложении; назавтра — просрочка
     * вместо «сегодня истекает». Обязательства приходят механизмом, показ — владельцем доставки.
     */
    @Test
    fun theExpiryCalendarWarnsTheSourceAndBannersEveryone() = runBlocking {
        val lastDay = today.plusDays(3)
        val start = Scenarios(database, now)
        val source = added(start, HOME_KIT, "Парацетамол", "20", expiresOn = lastDay)
        val plain = added(start, HOME_KIT, "Ибупрофен", "20", expiresOn = lastDay)
        treated(start, "Парацетамол курсом", today, 10, listOf(source to 10))

        fun on(day: LocalDate) = Scenarios(database, day.atTime(15, 0).toInstant(ZoneOffset.UTC))
        suspend fun shownOn(scenarios: Scenarios, kind: NotificationKind): Set<Uuid> {
            Mechanisms(scenarios, scenarios.now).use { mechanisms ->
                scenarios.dailyRound.run()
                mechanisms.await("проход после сверки") { mechanisms.outbox.state.value.passes >= 1 }
                mechanisms.settle()
            }
            return scenarios.notifier.shown.filter { it.kind == kind }.map { it.key }
                .map { it.subject.substringBefore('@') }.map { Uuid.parse(it) }.toSet()
        }

        assertEquals(setOf(source), shownOn(on(lastDay.minusDays(3)), NotificationKind.EXPIRY_SOURCE_3D))
        assertEquals(setOf(source), shownOn(on(lastDay.minusDays(1)), NotificationKind.EXPIRY_SOURCE_1D))

        val last = on(lastDay)
        assertEquals(emptySet<Uuid>(), shownOn(last, NotificationKind.EXPIRY_SOURCE_1D))
        // Баннеры приложения — и срок, и пропуски лечения источника (C1 «Попап пропущенного»); здесь
        // история о сроке, поэтому спрашиваются только его.
        val banners = last.reminderStore.observeAwaiting(NoticeDelivery.IN_APP_BANNER).first().filter { it.kind == NotificationKind.EXPIRY_TODAY }
        assertEquals(setOf(source, plain), banners.map { Uuid.parse(it.key.subject.substringBefore('@')) }.toSet())
        last.reminderOutbox.bannerShown(banners.map { it.key })
        assertTrue(last.reminderStore.observeAwaiting(NoticeDelivery.IN_APP_BANNER).first().none { it.kind == NotificationKind.EXPIRY_TODAY })

        val after = on(lastDay.plusDays(1))
        after.dailyRound.run()
        assertTrue(after.reminderStore.observeAwaiting(NoticeDelivery.IN_APP_BANNER).first().none { it.kind == NotificationKind.EXPIRY_TODAY })
        val listed = database.packageRepository().list(PackageQuery(medKitId = HOME_KIT, filter = PackageQuery.Filter.Expired), lastDay.plusDays(1)).first()
        assertEquals(setOf(source, plain), listed.map { it.id }.toSet())
    }
}
