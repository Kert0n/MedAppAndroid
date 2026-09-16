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
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
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
import kotlinx.coroutines.cancel
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

    private fun model() = ExpiringTodayViewModel(
        outbox = scenarios.reminderOutbox,
        reminders = scenarios.reminderStore,
        packages = database.packageRepository()
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
            state.awaiting(PATIENTLY) { !it.isEmpty }
            model.dismiss()
            state.awaiting(PATIENTLY) { it.isEmpty }
        }

        assertTrue(scenarios.reminderStore.awaiting(NoticeDelivery.IN_APP_BANNER).isEmpty())
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

    private companion object {
        val PATIENTLY: Duration = 15.seconds
    }
}
