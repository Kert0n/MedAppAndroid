package com.kert0n.medapp.feature.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Что делает владелец доставки, когда сказать **не вышло**. Раньше он знал только «показал / не
 * показал» и в обоих случаях ставил будильник на тот же — уже прошедший — срок.
 *
 * Причины у неуспеха разные, и поведение их различает (решение владельца 2026-09-14): структурная —
 * разрешения нет, будиться бессмысленно, обязательство ждёт экрана; сбой — повторяем через отступ;
 * повода больше нет — снимаем.
 */
@RunWith(AndroidJUnit4::class)
class ReminderOutboxTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T09:00:00Z")

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
    }

    @After
    fun tearDown() = database.close()

    private fun digest(day: LocalDate = LocalDate.of(2027, 3, 10), at: Instant = now.minusSeconds(600)) =
        Reminder(NotificationKey.digest(day), NotificationTarget.DayPlan(day), at)

    /** Напоминание о приёме: только у него владелец доставки ходит за свежестью перед показом. */
    private fun intake(id: Uuid = Uuid.random(), at: Instant = now.minusSeconds(600)) =
        Reminder(NotificationKey.intake(id, NotificationKind.INTAKE_DUE), NotificationTarget.Intake(id), at)

    private fun coverage(course: Uuid = Uuid.random(), at: Instant = now.minusSeconds(600)) =
        Reminder(NotificationKey.reduction(course), NotificationTarget.CourseSources(course), at)

    /**
     * Баннер дня показывает экран, а отмечает показ владелец доставки — один и для системы, и
     * для экрана. Отмеченный второй раз не наступает; системное обязательство под этой отметкой
     * не меняется; назавтра сверка снимает баннер сама — просрочка вместо «сегодня истекает».
     */
    @Test
    fun aBannerShownByTheScreenIsRecordedOnceByTheOwner() = runTest {
        val day = LocalDate.of(2027, 3, 10)
        val expiry = com.kert0n.medapp.domain.pack.ExpiryDate(day)
        val banner = Reminder(NotificationKey.expiry(PACK, expiry, NotificationKind.EXPIRY_TODAY), NotificationTarget.PackageCard(PACK), now.minusSeconds(60))
        val system = digest()
        scenarios.reminderStore.saveAll(listOf(banner, system))

        scenarios.reminderOutbox.bannerShown(listOf(banner.key, system.key))

        assertEquals(Reminder.State.SHOWN, requireNotNull(scenarios.reminderStore.find(banner.key)).state)
        assertEquals("системное обязательство экрану не принадлежит", Reminder.State.DUE, requireNotNull(scenarios.reminderStore.find(system.key)).state)
        assertTrue(scenarios.reminderStore.awaiting(com.kert0n.medapp.domain.notification.NoticeDelivery.IN_APP_BANNER).isEmpty())
        // Повторная отметка ничего не ломает: показанное остаётся показанным.
        scenarios.reminderOutbox.bannerShown(listOf(banner.key))
        assertEquals(Reminder.State.SHOWN, requireNotNull(scenarios.reminderStore.find(banner.key)).state)
    }

    /**
     * **Структурная причина: будиться незачем.** Разрешения нет — показ не состоится, сколько ни
     * буди. Обязательство остаётся невыполненным и ждёт экрана, а будильник на прошедший срок не
     * ставится: иначе система разбудит процесс немедленно, проход снова не покажет, и так без конца.
     */
    @Test
    fun aStructurallyBlockedShowingAsksForNoAlarm() = runTest {
        scenarios.reminderStore.saveAll(listOf(digest()))
        scenarios.notifier.allowed = false

        scenarios.reminderOutbox.pass()
        scenarios.reminderOutbox.pass()

        assertEquals(Reminder.State.DUE, requireNotNull(scenarios.reminderStore.find(digest().key)).state)
        assertNull("будильник на прошедший срок будит процесс без конца", scenarios.reminders.wakeAt)
    }

    /**
     * Сбой — не отказ: повторяем, но не сейчас же. Проход обязан **запланировать** возврат — срок
     * впереди, а не в прошлом и не «никогда». Сейчас исключение уносит проход целиком, будильник не
     * ставится вовсе, и расписание встаёт до ближайшего входа в приложение.
     */
    @Test
    fun aFailedShowingIsRetriedLaterNotImmediately() = runTest {
        val stale = digest()
        scenarios.reminderStore.saveAll(listOf(stale))
        scenarios.notifier.failing += stale.key

        runCatching { scenarios.reminderOutbox.pass() }

        val woken = scenarios.reminders.wakeAt
        assertTrue("сбой обязан запланировать возврат, а будильника нет вовсе", woken != null)
        assertTrue("возврат назначен на прошлое: $woken", woken!!.isAfter(now))
        assertEquals(Reminder.State.DUE, requireNotNull(scenarios.reminderStore.find(stale.key)).state)
    }

    /**
     * Сбой одного показа не уносит остальные и не оставляет расписание без будильника: проход
     * изолирует его, как работник очереди изолирует сбой одной операции.
     */
    @Test
    fun oneFailedShowingDoesNotStallTheRest() = runTest {
        val first = coverage()
        val broken = coverage()
        val third = digest()
        scenarios.reminderStore.saveAll(listOf(first, broken, third))
        scenarios.notifier.failing += broken.key

        runCatching { scenarios.reminderOutbox.pass() }

        assertEquals(
            "сказать надо было всё, кроме сорвавшегося",
            setOf(first.key, third.key),
            scenarios.notifier.shown.map { it.key }.toSet()
        )
    }

    /** Повода больше нет — обязательство снимается, а не висит вечно, прося будильник на прошлое. */
    @Test
    fun aVanishedSubjectWithdrawsItsObligation() = runTest {
        val gone = coverage()
        scenarios.reminderStore.saveAll(listOf(gone))
        scenarios.notifier.vanished += gone.key

        scenarios.reminderOutbox.pass()
        scenarios.reminderOutbox.pass()

        assertNull("обязательство без повода должно уйти", scenarios.reminderStore.find(gone.key))
        assertNull(scenarios.reminders.wakeAt)
    }

    /**
     * Срок хранения считается от **последнего** показа: отложенное показывают снова, и забывать его
     * по первому показу — значит уничтожить живое обязательство.
     */
    @Test
    fun retentionCountsFromTheLastShowing() = runTest {
        val reminder = digest(at = now.minusSeconds(60))
        scenarios.reminderStore.saveAll(listOf(reminder))
        scenarios.reminderOutbox.pass()

        // Человек отложил, и обязательство сказали второй раз — много позже первого.
        val muchLater = now.plus(java.time.Duration.ofDays(20))
        requireNotNull(scenarios.reminderStore.find(reminder.key)).let {
            it.defer(muchLater)
            scenarios.reminderStore.saveAll(listOf(it))
        }
        Scenarios(database, muchLater, notifier = scenarios.notifier).reminderOutbox.pass()

        // Спустя ещё двадцать дней первый показ старше срока хранения, а последний — нет.
        val afterRetention = muchLater.plus(java.time.Duration.ofDays(20))
        Scenarios(database, afterRetention, notifier = scenarios.notifier).reminderOutbox.pass()

        assertTrue(
            "обязательство забыто по первому показу, хотя сказано было недавно",
            scenarios.reminderStore.find(reminder.key) != null
        )
    }

    /**
     * Повод вернулся раньше, чем владелец успел забыть отозванное: обязательство должно воскреснуть
     * и дойти до человека, а не погаснуть непоказанным.
     */
    @Test
    fun aReturningReasonRevivesAnUnshownWithdrawal() = runTest {
        val notice = coverage()
        scenarios.reminderStore.saveAll(listOf(notice))
        scenarios.reminderWithdrawal.withdrawKeys(listOf(notice.key))

        // Сверка снова видит повод — до того, как владелец доставки погасил отозванное.
        scenarios.reminderPromising.promise(listOf(notice))
        scenarios.reminderOutbox.pass()

        assertEquals(
            "вернувшийся повод должен быть сказан, а не погашен непоказанным",
            listOf(notice.key),
            scenarios.notifier.shown.map { it.key }
        )
    }

    /**
     * **Разрешение дали, потом забрали.** Анна разрешила уведомления и получила напоминание. Через
     * неделю она отобрала разрешение в системных настройках — приложение об этом не спрашивают, оно
     * узнаёт только по отказу показа.
     *
     * Дальше: уже сказанное остаётся сказанным; новое наступившее не теряется и ждёт экрана; а
     * будильник на него не ставится — будиться ради показа, который заведомо не состоится, значит
     * жечь батарею впустую. Сейчас каждый такой проход ставит будильник на прошедший срок, и
     * устройство будится без конца.
     */
    @Test
    fun permissionTakenAwayStopsTheAlarmsButNotTheObligations() = runTest {
        val said = digest(at = now.minusSeconds(600))
        scenarios.reminderStore.saveAll(listOf(said))
        scenarios.reminderOutbox.pass()
        assertEquals(listOf(said.key), scenarios.notifier.shown.map { it.key })

        // Разрешение отобрали, и наступает следующее обязательство.
        scenarios.notifier.allowed = false
        val silenced = coverage(at = now.minusSeconds(60))
        scenarios.reminderStore.saveAll(listOf(silenced))

        scenarios.reminderOutbox.pass()
        scenarios.reminderOutbox.pass()

        assertEquals("сказанное до отзыва остаётся сказанным", listOf(said.key), scenarios.notifier.shown.map { it.key })
        assertEquals(
            "обязательство не потеряно — его ждёт экран списка приёмов",
            Reminder.State.DUE,
            requireNotNull(scenarios.reminderStore.find(silenced.key)).state
        )
        assertNull("будить ради невозможного показа незачем", scenarios.reminders.wakeAt)
    }

    /**
     * И обратно: разрешение вернули — накопленное доходит. Обязательство всё это время лежало
     * невыполненным, поэтому говорить заново его никто не заводит: оно просто наконец наступает.
     */
    @Test
    fun permissionGivenBackDeliversWhatWasWaiting() = runTest {
        val waiting = coverage(at = now.minusSeconds(60))
        scenarios.reminderStore.saveAll(listOf(waiting))
        scenarios.notifier.allowed = false
        scenarios.reminderOutbox.pass()
        assertEquals(emptyList<NotificationKey>(), scenarios.notifier.shown.map { it.key })

        scenarios.notifier.allowed = true
        scenarios.reminderOutbox.pass()

        assertEquals(listOf(waiting.key), scenarios.notifier.shown.map { it.key })
    }

    /**
     * **В шторку — только в свой день** (PLAN C1). Максим три дня жил с запретом и в среду его снял.
     * Утренний приём ещё о сегодня — его сказать стоит. Вчерашняя сводка — уже нет: в шторке она
     * утонула бы среди сегодняшнего и открыла бы день, которого нет.
     * Непоказанное за пределом остаётся ждать полку, а не снимается. Очередь, ждущая решения, —
     * без предела: решать её нужно и назавтра.
     *
     * Без предела снятый запрет вываливает в шторку все пропуски за месяц разом.
     */
    @Test
    fun onlyTodaysNewsIsToldOnceSpeakingIsAllowedAgain() = runTest {
        val yesterday = now.minus(java.time.Duration.ofHours(14))
        val missed = digest(day = LocalDate.of(2027, 3, 9), at = yesterday)
        val morning = intake(at = now.minusSeconds(3600))
        val queue = Reminder(NotificationKey.sync(Uuid.random()), NotificationTarget.SyncStatus, yesterday)
        scenarios.reminderStore.saveAll(listOf(missed, morning, queue))
        scenarios.notifier.allowed = false
        scenarios.reminderOutbox.pass()

        scenarios.notifier.allowed = true
        scenarios.reminderOutbox.pass()

        assertEquals(setOf(morning.key, queue.key), scenarios.notifier.shown.map { it.key }.toSet())
        assertEquals(
            "вчерашнее не сказано и не снято: его судьбу решает сверка",
            Reminder.State.DUE,
            requireNotNull(scenarios.reminderStore.find(missed.key)).state
        )
        assertNull("будить ради вчерашнего незачем", scenarios.reminders.wakeAt)
    }

    /**
     * **Гонка «показ против отмены».** Владелец доставки читает обязательства, идёт за свежестью —
     * это до двух секунд, — и только потом показывает. За это время лечение могли отменить: отмена
     * снимает обязательство своей транзакцией.
     *
     * Записать после показа то, что читали до сети, значит воскресить снятое: карточка останется
     * висеть, а обязательство будет числиться сказанным. Перечитывать надо.
     */
    @Test
    fun aWithdrawalDuringTheNetworkWaitIsNotOverwritten() = runTest {
        val reminder = intake()
        scenarios.reminderStore.saveAll(listOf(reminder))
        scenarios.freshness.meanwhile = { scenarios.reminderWithdrawal.withdrawKeys(listOf(reminder.key)) }

        scenarios.reminderOutbox.pass()

        assertEquals(
            "снятое обязательство воскрешено показом",
            Reminder.State.WITHDRAWN,
            requireNotNull(scenarios.reminderStore.find(reminder.key)).state
        )
    }

    /**
     * **Гонка «показ против отсрочки».** В том же окне человек мог нажать «Отложить» по прежней
     * карточке этого пункта. Пометить обязательство сказанным значит потерять отсрочку: человек
     * просил напомнить позже, а напоминание не придёт больше никогда.
     */
    @Test
    fun aSnoozeDuringTheNetworkWaitIsNotLost() = runTest {
        val reminder = intake()
        scenarios.reminderStore.saveAll(listOf(reminder))
        val later = now.plusSeconds(15 * 60)
        scenarios.freshness.meanwhile = {
            requireNotNull(scenarios.reminderStore.find(reminder.key)).let {
                it.defer(later)
                scenarios.reminderStore.saveAll(listOf(it))
            }
        }

        scenarios.reminderOutbox.pass()

        val after = requireNotNull(scenarios.reminderStore.find(reminder.key))
        assertEquals("отсрочка потеряна: записано прочитанное до сети", later, after.dueAt)
        assertEquals(Reminder.State.DUE, after.state)
        // И в шторке отложенного нет: наступившее перечитано после ожидания, а не показано по
        // объекту, прочитанному до него.
        assertTrue("отложенное показано", scenarios.notifier.shown.none { it.key == reminder.key })
        assertEquals("будильник — на новый срок", later, scenarios.reminders.wakeAt)
    }
}
