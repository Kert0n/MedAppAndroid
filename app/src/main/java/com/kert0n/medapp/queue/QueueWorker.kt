package com.kert0n.medapp.queue

import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSnapshot
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Работник очереди: читает, что у сервера сейчас, готовит запрос по прочитанному, отправляет,
 * записывает ответ, применяет его и отпускает. Сервер — истина по количеству; устройство
 * доставляет случившееся поверх свежего состояния и читает истину обратно (PLAN E2, E3).
 * Поэтому проход по пачке начинается с чтения её снимка, а следующие операции той же пачки
 * готовятся по ответу предыдущей — он уже лёг в базу. Запрос, замороженный раньше, — повтор с
 * неизвестным исходом либо отправка, пережившая смерть процесса, — уходит как есть.
 *
 * Полученная квитанция записывается до того, как применена: если применить её нечем — словарь не
 * знает единицы, аптечка снимка неизвестна, снимок следом не прочитался, — операция ждёт с
 * квитанцией в руках и закрывается из неё, не спрашивая сервер второй раз. Везёт и читает
 * [Courier]: он же собирает снимок в домен, и хранение получает уже собранный снимок.
 *
 * Один проход — [drain]: пока есть связь, по одной операции в порядке очереди. Обрыв оставляет
 * операцию на повтор тем же запросом и останавливает проход; ограничение частоты соблюдает
 * `Retry-After`; строка, которую нечем прочитать, пропускается, а промах словаря дочитывается.
 * Задержка между повторами растёт с попытками — от двух секунд до пяти минут.
 *
 * Сбой одной операции — не сбой прохода: исключение из её шага ловится здесь, операция помечена
 * (ждёт повтора с задержкой либо, если ответ уже записан, ждёт с ним в руках) и названа в
 * [Report.failed], а проход идёт дальше. Владелец прохода — [QueueOutbox] — ловит остальное.
 */
@Singleton
class QueueWorker @Inject constructor(
    private val storage: QueueStorage,
    private val courier: Courier,
    private val clock: Clock
) {

    /**
     * Исполнитель один: два прохода разом отправили бы одну операцию дважды. Второй вызов ждёт
     * первого, а не пропускается, — тот, кто позвал, хочет, чтобы очередь ушла.
     */
    private val single = Mutex()

    /**
     * Поездка курьера текущего прохода: промах словаря дочитывается один раз на проход, а не на
     * каждую операцию. Проход один — [single], — поэтому и поездка у него одна.
     */
    private var trip: Courier.Pass = courier.pass()

    /**
     * Проход: пока в базе есть готовая операция — берётся первая по номеру, и так до тех пор,
     * пока готовых не останется или связь не оборвётся. Готовность — одно определение, и живёт
     * оно в запросе ([QueueStorage.ready]): срок, зависимости, порядок по пачке. Поэтому
     * переподготовленная операция уходит тем же проходом, а зависимая — сразу за родителем.
     * Промах словаря дочитывается один раз; строка, которой не помог и свежий словарь, —
     * пропуск, а не бесконечный круг. Переподготовка одной операции — не больше трёх раз
     * подряд: дальше она ждёт по обычной задержке.
     */
    suspend fun drain(): Report = single.withLock {
        val drain = Drain()
        trip = courier.pass()
        while (true) {
            val entry = storage.ready(clock.instant()).firstOrNull { it.id !in drain.skippedIds } ?: break
            val operation = when (entry) {
                is StoredSyncOperation.Readable -> entry.operation
                // Словаря не хватило: дочитывается один раз за проход, и строка читается снова.
                is StoredSyncOperation.Stale -> {
                    if (trip.refreshVocabularyOnce()) continue
                    // Сервер словаря не дал — строка ждёт следующего захода; дал, а её единицы в
                    // нём нет — ждать нечего, и строка пропущена.
                    if (trip.vocabularyRefreshFailed) drain.hold(entry.id) else drain.skip(entry.id, entry.miss.message.orEmpty())
                    continue
                }
                is StoredSyncOperation.Unreadable -> {
                    drain.skip(entry.id, entry.reason)
                    continue
                }
            }
            val packageId = (operation.command as? PackageSyncCommand)?.packageId
            val stop = try {
                val step = if (operation.awaitsApplication) resume(operation) else attempt(operation, packageId, drain)
                drain.record(operation, step)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Ответ, записанный до сбоя, остаётся у операции: она ждёт с ним, а не повторяет запрос.
                val answered = operation.awaitsApplication || operation.id in drain.answeredIds
                drain.record(operation, Step.Failed(failure, answered))
            }
            if (stop) break
        }
        drain.report()
    }

    /** Подготовка по свежему состоянию, отправка, запись ответа и его применение — одна операция. */
    private suspend fun attempt(operation: SyncOperation, packageId: Uuid?, pass: Drain): Step {
        // «Унёс домой» читает полку всегда: подтверждённое ею число к моменту снятия — половина
        // остатка коробки, а снимки прохода на местную полку числа не кладут (PLAN E6).
        val fresh = if (operation.prepared == null && packageId != null &&
            (packageId !in pass.freshPackages || operation.command is PackageSyncCommand.Withdraw) &&
            operation.command !is PackageSyncCommand.Create
        ) {
            when (val read = snapshotRead(packageId)) {
                is Read.Snapshot -> read.snapshot
                is Read.Failed -> return Step.Settled(read.delivery, stop = read.stop)
            }
        } else {
            null
        }
        val taken = when (val take = storage.take(operation.id, fresh, clock.instant())) {
            null -> return Step.Skipped
            is Take.Closed -> {
                packageId?.let(pass.freshPackages::add)
                return Step.Closed(take.delivery)
            }
            is Take.Sending -> take.operation
        }
        pass.sentIds += taken.id
        packageId?.let(pass.freshPackages::add)
        val request = checkNotNull(taken.prepared) { "взятая в отправку операция несёт запрос" }
        return when (val status = trip.send(request)) {
            is DeliveryStatus.Received -> {
                // Квитанция записана до применения: полученное подтверждение не теряется — и при
                // сбое применения операция ждёт с ней в руках, а не уходит на повтор.
                storage.answered(taken.id, status.receipt, clock.instant())
                pass.answeredIds += taken.id
                resolve(taken.command, status.receipt)
            }
            // Версия устарела — реестр отверг поручение до применения; «номер занят» о версии не говорит.
            DeliveryStatus.Outdated -> Step.Settled(stale(taken, request))
            DeliveryStatus.Taken -> Step.Settled(conflict(taken.command))
            DeliveryStatus.VersionMissing -> Step.Settled(refused(taken.command, RefusalReason.INVALID))
            DeliveryStatus.Invalid ->
                Step.Settled(refused(taken.command, (taken.command as? PackageSyncCommand)?.onInvalid ?: RefusalReason.INVALID))
            DeliveryStatus.Absent -> Step.Settled(notFound(taken, request))
            // Пропуска нет окончательно: перевыпуск и один повтор уже были у курьера (PLAN B5), и
            // реестр этой учётке не отвечает. Проход останавливается, а операция ждёт по задержке —
            // иначе она осталась бы готовой сейчас же, и собственная запись разбудила бы следующий круг.
            DeliveryStatus.NoPass -> Step.Settled(Delivery.Retry("нет пропуска"), stop = true)
            is DeliveryStatus.Throttled -> Step.Settled(Delivery.Retry("429"), retryAfter = status.retryAfter, stop = true)
            DeliveryStatus.Unreachable -> Step.Settled(Delivery.Retry("связи нет", attempted = false), stop = true)
            DeliveryStatus.OutcomeUnknown -> Step.Settled(Delivery.Retry("ответ потерян", outcomeUnknown = true))
            is DeliveryStatus.Garbled -> Step.Settled(Delivery.Retry(status.reason, outcomeUnknown = true))
        }
    }

    /** Квитанция уже записана — применить её; реестр о ней больше не спрашивают. */
    private suspend fun resume(operation: SyncOperation): Step =
        resolve(operation.command, checkNotNull(operation.answer) { "операция с ответом несёт его" })

    /**
     * Применение записанной квитанции: курьер читает её по форме, которую ждала команда, и истина
     * по пачке — из квитанции либо чтением следом. Квитанция не по форме — исход неизвестен, повтор
     * тем же запросом. Применить нечем — операция ждёт с квитанцией в руках.
     */
    private suspend fun resolve(command: SyncCommand, receipt: Receipt): Step {
        val read = trip.read(receipt, command.expects)
        return when (command) {
            is PackageSyncCommand -> when (read) {
                is Answer.Garbled -> garbled(read)
                is Answer.Snapshot -> Step.Settled(Delivery.Applied(PackageState.Present(read.snapshot)))
                // Команда применена, а коробка уже на полке, где нас нет: ответ окончательный (E6).
                Answer.Elsewhere -> Step.Settled(Delivery.Applied(PackageState.Elsewhere))
                is Answer.Unresolved -> Step.Deferred(read.reason, stop = read.stop)
                Answer.Gone -> Step.Settled(Delivery.Applied(PackageState.Gone))
                Answer.Claim, Answer.Nothing ->
                    if (command is PackageSyncCommand.Delete || command is PackageSyncCommand.Withdraw) {
                        Step.Settled(Delivery.Applied(PackageState.Gone))
                    } else {
                        when (val snapshot = snapshotRead(command.packageId)) {
                            is Read.Snapshot -> Step.Settled(Delivery.Applied(PackageState.Present(snapshot.snapshot)))
                            is Read.Failed -> when (snapshot.delivery) {
                                Delivery.AccessLost -> Step.Settled(Delivery.AccessLost)
                                else -> Step.Deferred((snapshot.delivery as Delivery.Retry).error, stop = snapshot.stop)
                            }
                        }
                    }
            }
            is MedKitSyncCommand -> if (read is Answer.Garbled) garbled(read) else Step.Settled(Delivery.Applied(PackageState.None))
            else -> command.unknownRoot()
        }
    }

    /** Квитанция не по форме: исход неизвестен, повтор тем же запросом. */
    private fun garbled(read: Answer.Garbled): Step = Step.Settled(Delivery.Retry(read.reason, outcomeUnknown = true))

    /**
     * Версия устарела — сервер отверг запрос до применения, в журнал он не попал: гонка длиной в
     * секунды. Любая команда пачки готовится заново по свежему состоянию под тем же номером —
     * разница человека ложится поверх чужого изменения, а сводима ли она, решает подготовка
     * (PLAN E3, C1). Прежде смотрится, не применён ли запрос уже: у курсового расхода потерянный
     * ответ оставляет след в `mine`; у пересчёта, чей исход неизвестен, — число, к которому он
     * вёл. Разница, переподготовленная поверх себя же, применилась бы дважды.
     */
    private suspend fun stale(operation: SyncOperation, request: PreparedRequest): Delivery = when (val command = operation.command) {
        is PackageSyncCommand -> snapshotThen(command.packageId) { snapshot ->
            val applied = when (command) {
                is PackageSyncCommand.Consume -> command.provenAppliedBy(snapshot, request)
                is PackageSyncCommand.CorrectStock -> operation.outcomeUnknown && command.provenAppliedBy(snapshot, request)
                else -> false
            }
            if (applied) Delivery.Applied(PackageState.Present(snapshot)) else Delivery.Stale(snapshot)
        }
        is MedKitSyncCommand -> Delivery.Refused(RefusalReason.STALE, PackageState.None)
        else -> command.unknownRoot()
    }

    /**
     * 409 — не о версии (её отвергает 412), а о занятом номере: объект с ним уже есть, бронь уже
     * заявлена или под этим номером уже применили другое тело. Последнее — дефект клиента:
     * переподготовка тела не меняет, и сервер ответит так же всегда (PLAN E3).
     *
     * **Занятый номер сам по себе не значит «наше»** (PLAN C0): желаемое уже так, только если мы
     * этот объект видим. Поэтому и у пачки, и у аптечки 409 объясняется чтением, а не догадкой —
     * иначе чужая полка объявлялась бы опубликованной нами, и мы начали бы класть в неё коробки.
     * Не дочитали — повтор: тот же запрос снова даст 409, и вопрос будет задан заново.
     */
    private suspend fun conflict(command: SyncCommand): Delivery = when (command) {
        is PackageSyncCommand -> when (command.onConflict) {
            ConflictPolicy.EXISTS -> snapshotThen(command.packageId) { Delivery.Applied(PackageState.Present(it)) }
            ConflictPolicy.REPREPARE -> snapshotThen(command.packageId) { Delivery.Stale(it) }
            ConflictPolicy.REFUSE -> refused(command, RefusalReason.INVALID)
        }
        is MedKitSyncCommand -> when (trip.medKitIsOurs(command.medKitId)) {
            true -> Delivery.Applied(PackageState.None)
            false -> Delivery.Refused(RefusalReason.INVALID, PackageState.None)
            null -> Delivery.Retry("занятый номер аптечки не проверен")
        }
        else -> command.unknownRoot()
    }

    /**
     * Отказ по вводу закрывает операцию: повторять нечем и незачем. Что теперь правда, говорит
     * снимок — кроме создания: пачки на сервере нет по построению, и читать нечего.
     */
    private suspend fun refused(command: SyncCommand, reason: RefusalReason): Delivery = when (command) {
        is PackageSyncCommand.Create -> Delivery.Refused(reason, PackageState.None)
        is PackageSyncCommand -> snapshotThen(command.packageId) { Delivery.Refused(reason, PackageState.Present(it)) }
        else -> Delivery.Refused(reason, PackageState.None)
    }

    /**
     * 404 значит разное для разных команд (PLAN B4): что именно — говорит команда. У расхода и
     * пересчёта есть ещё один случай: повтор запроса, который уже уходил с неизвестным исходом и
     * мог уничтожить пачку, дойдя до нуля, — тогда пачки нет по нашей же причине, и это применение,
     * а не потеря доступа (PLAN E3). Известный исход — 429, обрыв до сервера — такого не значит.
     */
    private suspend fun notFound(operation: SyncOperation, request: PreparedRequest): Delivery = when (val command = operation.command) {
        is PackageSyncCommand -> when (command.onNotFound) {
            NotFoundPolicy.ACCESS_LOST ->
                if (operation.outcomeUnknown && command.emptiedBy(request)) {
                    Delivery.Applied(PackageState.Gone)
                } else {
                    Delivery.AccessLost
                }
            NotFoundPolicy.REPREPARE -> snapshotThen(command.packageId) { Delivery.Stale(it) }
            NotFoundPolicy.APPLIED ->
                if (command is PackageSyncCommand.ReleaseClaim) snapshotThen(command.packageId) { Delivery.Applied(PackageState.Present(it)) }
                else Delivery.Applied(PackageState.Gone)
            // Полки, куда кладут коробку, не стало. Коробка при этом никуда не делась — она у
            // человека в руках, — поэтому это отказ, а не её конец: снимка читать не у кого,
            // а вернуть её на прежнее место умеет закрытие (PLAN E6).
            NotFoundPolicy.REFUSE -> Delivery.Refused(RefusalReason.STALE, PackageState.None)
        }
        // Аптечки нет или мы не участник: удаление и выход тем самым исполнены (PLAN E3).
        is MedKitSyncCommand -> Delivery.Applied(PackageState.None)
        else -> command.unknownRoot()
    }

    /** Истина по пачке, прочитанная следом, — и исход по ней; не прочиталась — исход чтения. */
    private suspend fun snapshotThen(packageId: Uuid, then: (PackageSnapshot) -> Delivery): Delivery =
        when (val read = snapshotRead(packageId)) {
            is Read.Snapshot -> then(read.snapshot)
            is Read.Failed -> read.delivery
        }

    /**
     * Что у реестра сейчас по этой пачке — собранным снимком: неизвестное дочитывается или ждёт.
     * Пачки нет — доступа к ней нет; связи нет — проход останавливается; иначе — повтор позже.
     */
    private suspend fun snapshotRead(packageId: Uuid): Read = when (val read = trip.packageSnapshot(packageId)) {
        is Fetched.Snapshot -> Read.Snapshot(read.snapshot)
        // Коробка на полке, где нас нет: отправлять некуда — это утрата доступа, а не повтор (E6).
        Fetched.Elsewhere -> Read.Failed(Delivery.AccessLost)
        is Fetched.Unresolved -> Read.Failed(Delivery.Retry(read.reason), stop = read.stop)
        is Fetched.Declined -> when (val status = read.status) {
            DeliveryStatus.Absent -> Read.Failed(Delivery.AccessLost)
            DeliveryStatus.NoPass -> Read.Failed(Delivery.Retry("нет пропуска"), stop = true)
            is DeliveryStatus.Throttled -> Read.Failed(Delivery.Retry("429"), stop = true)
            DeliveryStatus.Unreachable -> Read.Failed(Delivery.Retry("связи нет", attempted = false), stop = true)
            else -> Read.Failed(Delivery.Retry("снимок не прочитан: $status"))
        }
    }

    private sealed interface Read {
        data class Snapshot(val snapshot: PackageSnapshot) : Read
        data class Failed(val delivery: Delivery, val stop: Boolean = false) : Read
    }

    /** Две секунды после первой неудачи, удвоение с каждой следующей, не дольше пяти минут. */
    private fun backoff(attempts: com.kert0n.medapp.domain.value.Attempts): Duration =
        (INITIAL_BACKOFF * (1 shl minOf(attempts.count - 1, MAX_BACKOFF_STEPS).coerceAtLeast(0))).coerceAtMost(MAX_BACKOFF)

    /** Чем кончился шаг по одной операции. */
    private sealed interface Step {
        /** Исход установлен и записывается хранилищем. */
        data class Settled(val delivery: Delivery, val retryAfter: Duration? = null, val stop: Boolean = false) : Step

        /** Подготовка закрыла операцию сама — хранилище уже записало исход. */
        data class Closed(val delivery: Delivery) : Step

        /** Ответ записан, применить его пока нечем: операция ждёт с ответом в руках. */
        data class Deferred(val reason: String, val stop: Boolean = false) : Step

        /** Шаг бросил: операция помечена и названа, проход идёт дальше. [answered] — ответ уже записан. */
        data class Failed(val cause: Exception, val answered: Boolean) : Step


        data object Skipped : Step
    }

    /** Состояние одного прохода: что закрыто, что пропущено, какие пачки уже прочитаны. */
    private inner class Drain {
        private var settled = 0
        private val skipped = ArrayList<Report.Skipped>()
        private val failed = ArrayList<Report.Failure>()
        private var retryAt: Instant? = null
        private val reprepared = HashMap<Uuid, Int>()
        val skippedIds = HashSet<Uuid>()

        /** Пачки, чьё серверное состояние в этом проходе уже лежит в базе. */
        val freshPackages = HashSet<Uuid>()

        /** Операции, чей ответ в этом проходе уже записан: сбой после него — ожидание, а не повтор. */
        val answeredIds = HashSet<Uuid>()

        /**
         * Операции, взятые этим проходом в отправку: запрос мог дойти до сервера, и сбой после взятия
         * оставляет его исход неизвестным — как смерть процесса посреди отправки (PLAN E3).
         */
        val sentIds = HashSet<Uuid>()

        fun skip(id: Uuid, reason: String) {
            skipped += Report.Skipped(id, reason)
            skippedIds += id
        }

        /** Строка ждёт следующего захода: в этом её больше не берут, но и пропущенной она не стала. */
        fun hold(id: Uuid) {
            skippedIds += id
        }

        private fun retryNotBefore(at: Instant) {
            retryAt = if (retryAt == null || at.isBefore(retryAt)) at else retryAt
        }

        private fun later(operation: SyncOperation, wait: Duration? = null): Instant =
            clock.instant().plus((wait ?: backoff(operation.attempts.next())).toJavaDuration()).also(::retryNotBefore)

        /** Записывает шаг; `true` — проход надо остановить. */
        suspend fun record(operation: SyncOperation, step: Step): Boolean {
            when (step) {
                is Step.Settled -> {
                    val delivery = when (val delivery = step.delivery) {
                        // Срок повтора живёт в базе: следующий проход, процесс или второй
                        // `drain` его увидят, а операция раньше него готовой не будет.
                        is Delivery.Retry ->
                            delivery.copy(notBefore = later(operation, step.retryAfter ?: if (delivery.attempted) null else INITIAL_BACKOFF))
                        is Delivery.Stale -> {
                            val rounds = (reprepared[operation.id] ?: 0) + 1
                            reprepared[operation.id] = rounds
                            if (rounds >= MAX_REPREPARE_ROUNDS) delivery.copy(notBefore = later(operation)) else delivery
                        }
                        else -> delivery
                    }
                    storage.settle(operation.id, delivery.settlement(operation.command), clock.instant())
                    if (delivery is Delivery.Applied || delivery is Delivery.Refused || delivery is Delivery.AccessLost) settled++
                    return step.stop
                }
                is Step.Closed -> {
                    settled++
                    return false
                }
                is Step.Deferred -> {
                    storage.defer(operation.id, step.reason, clock.instant(), notBefore = later(operation))
                    return step.stop
                }
                is Step.Failed -> {
                    val reason = "сбой прохода: ${step.cause}"
                    failed += Report.Failure(operation.id, reason)
                    skippedIds += operation.id
                    if (step.answered) {
                        storage.defer(operation.id, reason, clock.instant(), notBefore = later(operation))
                    } else {
                        // Взятая в отправку — исход неизвестен: запрос мог примениться, а записать ответ
                        // не вышло. Иначе 412 на повторе переподготовил бы разницу поверх неё самой.
                        storage.settle(
                            operation.id,
                            Delivery.Retry(reason, notBefore = later(operation), outcomeUnknown = operation.id in sentIds)
                                .settlement(operation.command),
                            clock.instant()
                        )
                    }
                    return false
                }
                Step.Skipped -> {
                    // Взять не удалось — кто-то закрыл или взял её между чтением и взятием.
                    skippedIds += operation.id
                    return false
                }
            }
        }

        fun report() = Report(settled, skipped, retryAt, failed)
    }

    /**
     * Что сделал проход: сколько операций закрыто, какие строки пропущены как нечитаемые, какие
     * операции сбойнули и когда приходить снова — `null`, если ждать нечего.
     */
    data class Report(
        val settled: Int,
        val skipped: List<Skipped>,
        val retryAt: Instant?,
        val failed: List<Failure> = emptyList()
    ) {
        /** Строка, которую проход не собрал: названа с причиной, в базе не тронута. */
        data class Skipped(val id: Uuid, val reason: String)

        /** Операция, чей шаг бросил: названа с причиной, помечена в базе, ждёт повтора. */
        data class Failure(val id: Uuid, val reason: String)
    }

    private companion object {
        /** Столько раз подряд одна операция переподготавливается сразу; дальше — по задержке. */
        const val MAX_REPREPARE_ROUNDS = 3
        val INITIAL_BACKOFF: Duration = 2.seconds
        val MAX_BACKOFF: Duration = 5.minutes
        const val MAX_BACKOFF_STEPS = 8
    }
}
