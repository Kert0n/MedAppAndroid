package com.kert0n.medapp.fixture

import androidx.compose.runtime.mutableStateOf
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.feature.notification.DailyRound
import com.kert0n.medapp.feature.notification.NotificationReconciliation
import com.kert0n.medapp.feature.notification.NotificationUpkeep
import com.kert0n.medapp.feature.notification.ReminderAnswering
import com.kert0n.medapp.feature.notification.ReminderOutbox
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.feature.connectivity.Connection
import com.kert0n.medapp.platform.time.TimeShifts
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Мир истории человека (`docs/истории.md`): часы, которые двигает рассказ, шторка, в которой видно
 * сказанное, и **запущенные** владельцы доставки и сверки — как в `MedApp.onCreate`.
 *
 * Обязательства здесь руками не заводятся. Их заводят календарь, сверка и проход дня, а история
 * двигает время и нажимает. Иначе проверка сочиняет состояние, которого приложение не создаёт, и
 * зелёная история ничего не доказывает (разбор U5).
 *
 * Мир начинается **до** графа ([begin] в поле проверки) — модуль [TestNotificationModule] отдаёт
 * графу его часы и шторку — и кончается после теста ([end]): владельцы гаснут вместе с ним.
 */
class StoryWorld private constructor(start: Instant, zone: ZoneId) {

    val clock = StoryClock(start, zone)

    /** Шторка: что показано и погашено, и какие карточки висят сейчас ([FakeNotifier.cards]). */
    val shade = FakeNotifier().also { shade -> shade.allowedWhen = { TestPermissions.now().canSay(it.channel) } }

    val alarms = FakeReminders()

    val freshness = FakeFreshness()

    val daily = FakeDailySchedule()

    /** Нажатие на карточку уведомления: оболочка применяет его так же, как намерение окна. */
    val opening = mutableStateOf<NotificationTarget?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var outbox: ReminderOutbox
        private set

    private lateinit var upkeep: NotificationUpkeep
    private lateinit var shifts: TimeShifts
    private lateinit var round: DailyRound
    private lateinit var answering: ReminderAnswering

    /**
     * Запустить владельцев на частях графа. Отдельные экземпляры, а не одиночки графа: их область
     * гаснет в [end], и цикл прошлой истории не будит соседнюю проверку на закрытой базе.
     */
    fun start(
        reminders: ReminderStorageRepository,
        reconciliation: NotificationReconciliation,
        transactions: Transactions,
        shifts: TimeShifts,
        round: DailyRound,
        answering: ReminderAnswering,
        connection: Connection
    ) {
        takeTheNetworkAway(connection)
        this.shifts = shifts
        this.round = round
        this.answering = answering
        outbox = ReminderOutbox(reminders, shade, alarms, freshness, transactions, clock, scope).also { it.start() }
        upkeep = NotificationUpkeep(reminders, reconciliation, clock, scope).also { it.start() }
        runBlocking { await("владельцы доставки встали") { outbox.ready.value && upkeep.ready.value } }
    }

    /**
     * Время идёт: часы переходят в [at] (и, если названа, в зону [zone]), весть о переводе будит
     * ждущих границы суток, а владелец доставки проходит — как будильник, который бы сработал.
     */
    fun moveTo(at: Instant, zone: ZoneId = clock.zone) {
        clock.now = at
        clock.place = zone
        shifts.happened()
        outbox.runNow()
    }

    /**
     * Человек открыл приложение: проход дня и проход доставки — то, что делает вход
     * (`SyncTriggers`), и то же, что делает загрузка устройства (`BootAndTimeReceiver`).
     */
    suspend fun enter() {
        round.run()
        outbox.runNow()
        settle()
    }

    /** Механизмы затихли: число проходов доставки не менялось [quietMillis]. */
    suspend fun settle(quietMillis: Long = 500) {
        var seen = outbox.state.value.passes
        var quietFor = 0L
        while (quietFor < quietMillis) {
            kotlinx.coroutines.delay(50)
            val now = outbox.state.value.passes
            if (now == seen) quietFor += 50 else { seen = now; quietFor = 0 }
        }
    }

    /** Висит ли в шторке карточка о приёме [intakeId] этого вида. */
    fun cardUp(intakeId: Uuid, kind: NotificationKind = NotificationKind.INTAKE_DUE): Boolean =
        NotificationKey.intake(intakeId, kind) in shade.cards

    /** Нажатие на карточку: она гаснет (`setAutoCancel`), а приложение открывается с её целью. */
    suspend fun tap(reminder: Reminder) {
        shade.dismiss(reminder.key)
        opening.value = reminder.target
    }

    /**
     * Кнопка на карточке в шторке — то, что делает `NotificationActionReceiver`: ответ тем же
     * сценарием, затем карточка гаснет сразу. Системный приёмник вне системы не поднять: `goAsync`
     * у него есть только при настоящей доставке сигнала.
     */
    suspend fun inShade(intakeId: Uuid, action: NotificationAction): ReminderAnswering.Response {
        val response = when (action) {
            NotificationAction.TAKE -> answering.take(intakeId)
            NotificationAction.SKIP -> answering.skip(intakeId)
            NotificationAction.SNOOZE -> answering.snooze(intakeId)
        }
        shade.dismiss(NotificationKey.intake(intakeId, NotificationKind.INTAKE_DUE))
        return response
    }

    /**
     * **Связь отнимается на всю историю.** История живёт при сервере в памяти, а приложение
     * настроено на боевой адрес и под `-Pprobe` входит настоящей пробной учёткой — оставь ему
     * связь, и настоящий снимок уносит засеянные полки у истории из-под рук. Так `ShelfClearing`
     * терял коробки с дачи: список семи истекающих на глазах становился списком четырёх.
     *
     * Поддельному серверу отнятая связь не мешает: у него свой транспорт, а не порт устройства.
     */
    private fun takeTheNetworkAway(connection: Connection) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("svc wifi disable").close()
        automation.executeShellCommand("svc data disable").close()
        runBlocking { withTimeout(30_000) { connection.online.first { !it } } }
    }

    fun end() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("svc wifi enable").close()
        automation.executeShellCommand("svc data enable").close()
        scope.cancel()
        TestPermissions.reset()
        TestLanguages.reset()
        current = null
    }

    companion object {
        /** Идущая история; `null` — граф получает настоящие часы и уведомления. */
        @Volatile
        var current: StoryWorld? = null
            private set

        fun begin(start: Instant, zone: ZoneId): StoryWorld = StoryWorld(start, zone).also { current = it }
    }
}

/** Часы истории: момент и зону двигает рассказ ([StoryWorld.moveTo]). */
class StoryClock(@Volatile var now: Instant, @Volatile var place: ZoneId) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = place
    override fun withZone(zone: ZoneId): Clock = StoryClock(now, zone)
}
