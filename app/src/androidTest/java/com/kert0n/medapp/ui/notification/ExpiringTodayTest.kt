package com.kert0n.medapp.ui.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.feature.notification.ReminderReadings
import com.kert0n.medapp.feature.notification.ReminderRecords
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.await
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.notification.ExpiringTodayViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Попап «сегодня истекает» над настоящей базой: что в нём оказывается и что остаётся после
 * закрытия (PLAN D8). Как он выглядит, проверяет `ExpiringTodayPopupTest`.
 */
@RunWith(AndroidJUnit4::class)
class ExpiringTodayTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T06:00:00Z")

    private val opened = mutableListOf<ViewModel>()

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.packageRepository().add(
            pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM, expiresOn = ExpiryDate(LocalDate.of(2027, 3, 10)))
        )
    }

    @After
    fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    private fun model(
        outbox: com.kert0n.medapp.feature.notification.ReminderOutbox = scenarios.reminderOutbox,
        failures: com.kert0n.medapp.presentation.ScreenFailures = com.kert0n.medapp.presentation.ScreenFailures()
    ) = ExpiringTodayViewModel(
        outbox = outbox,
        reminders = scenarios.reminderStore,
        packages = database.packageRepository(),
        failures = failures
    ).also { opened += it }

    /** Обязательство сказать о сроке этой коробки — как его заводит сверка (PLAN D8). */
    private suspend fun promised(packageId: Uuid = PACK) {
        scenarios.reminderPromising.promise(
            listOf(
                Reminder(
                    key = NotificationKey(NotificationKind.EXPIRY_TODAY, packageId.toString()),
                    target = NotificationTarget.PackageCard(packageId),
                    dueAt = now
                )
            )
        )
    }

    /** Наступивший срок приходит попапом: коробка в нём — та, о которой обязательство. */
    @Test
    fun theBoxWhoseDayItIsComesUp() = runBlocking {
        promised()
        val model = model()

        val state = watching(model.state) { it.awaiting(PATIENTLY) { s -> !s.isEmpty } }

        assertEquals(listOf("Нурофен"), state.boxes.map { it.name })
    }

    /**
     * Крестик отмечает сказанное: обязательство уходит из невыполненных, и сегодня попап больше не
     * придёт. Без этого он встречал бы человека при каждом входе, пока коробка не просрочится.
     */
    @Test
    fun theCrossMarksTheNoticeAsTold() = runBlocking {
        promised()
        val model = model()

        watching(model.state) { state ->
            model.dismiss(state.awaiting(PATIENTLY) { !it.isEmpty }.told)
            state.awaiting(PATIENTLY) { it.isEmpty }
        }

        // Экран пустеет сразу, а отметка пишется следом: ждётся запись, а не мгновение после
        // крестика (прежняя проверка спрашивала таблицу раньше записи и падала через раз).
        await("крестик отметил сказанное") { scenarios.reminderStore.awaiting(NoticeDelivery.IN_APP_BANNER).isEmpty() }
    }

    /**
     * Коробку выбросили, пока попап открыт: она уходит из списка сама — содержимое живое, а не
     * снимок (решение владельца 2026-09-16).
     */
    @Test
    fun aBoxThrownAwayLeavesThePopupByItself() = runBlocking {
        database.packageRepository().add(
            pack(id = OTHER_PACK, name = "Цетрин", quantity = tablets("10"), form = TABLET_FORM, expiresOn = ExpiryDate(LocalDate.of(2027, 3, 10)))
        )
        promised()
        promised(OTHER_PACK)
        val model = model()

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.boxes.size == 2 }
            scenarios.packageRemoval.remove(OTHER_PACK)
            val left = state.awaiting(PATIENTLY) { it.boxes.size == 1 }
            assertEquals(listOf("Нурофен"), left.boxes.map { box -> box.name })
        }
    }

    /**
     * **Закрытый попап приходит назавтра.** Нина закрыла его в воскресенье, приложение живёт в
     * памяти до понедельника, и в понедельник истекает другая коробка. «Закрыто» — это сказанные
     * ключи, а не флаг экрана: новый ключ приходит сам. Запомни экран «закрыто» флагом — и
     * понедельничная новость не придёт, пока окно не пересоздадут.
     */
    @Test
    fun aClosedPopupComesBackWithTomorrowsNews() = runBlocking {
        database.packageRepository().add(
            pack(id = OTHER_PACK, name = "Цетрин", quantity = tablets("10"), form = TABLET_FORM, expiresOn = ExpiryDate(LocalDate.of(2027, 3, 11)))
        )
        promised()
        val model = model()

        watching(model.state) { state ->
            model.dismiss(state.awaiting(PATIENTLY) { !it.isEmpty }.told)
            state.awaiting(PATIENTLY) { it.isEmpty }
            promised(OTHER_PACK)
            val tomorrow = state.awaiting(PATIENTLY) { !it.isEmpty }
            assertEquals(listOf("Цетрин"), tomorrow.boxes.map { box -> box.name })
        }
    }

    private companion object {
        val PATIENTLY: Duration = 15.seconds
    }

    /** Отметку показа пишет доставка, а база ей отказывает — полный диск. */
    private fun outboxThatCannotWrite(scenarios: Scenarios) = com.kert0n.medapp.feature.notification.ReminderOutbox(
        object : ReminderRecords by scenarios.reminderStore {
            override suspend fun findAll(keys: Collection<com.kert0n.medapp.domain.notification.NotificationKey>) =
                throw IllegalStateException("database or disk is full")
        },
        scenarios.notifier, scenarios.reminders, scenarios.freshness, scenarios.transactions,
        java.time.Clock.fixed(scenarios.now, java.time.ZoneOffset.UTC),
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
    )

    /**
     * Крестик нажат, а отметка показа не записалась: попап возвращается, и закрыть его можно снова.
     * Без этого попап пропадает вместе с крестиком, а весть — нет: она придёт после перезапуска, и
     * человеку нечем её закрыть сейчас.
     */
    @Test
    fun aCrossThatCouldNotBeWrittenBringsThePopupBack(): Unit = runBlocking {
        promised()
        val failures = com.kert0n.medapp.presentation.ScreenFailures()
        val model = model(outboxThatCannotWrite(scenarios), failures)

        watching(model.state) { state ->
            val told = state.awaiting(PATIENTLY) { !it.isEmpty }.told
            val failed = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { failures.failures.first() }
            model.dismiss(told)
            kotlinx.coroutines.withTimeout(5_000) { failed.await() }
            // Состояние пересчитывается следом за крестиком: смотрят на него, когда оно устоялось,
            // иначе прочли бы ещё не спрятанный попап и позеленели зря.
            kotlinx.coroutines.delay(1_000)
            state.awaiting(PATIENTLY) { !it.isEmpty }
        }
    }
}
