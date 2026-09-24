package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.domain.intake.TakenDose
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Упаковка — конкретная коробка или флакон, который у нас есть; одинаковые названия пачки не
 * объединяют (PLAN C0). Сущность: пачка, из которой приняли таблетку, — та же пачка, равенство по
 * [id], состояние меняют переходы. [quantity] — подтверждённый остаток (E1); обвязка
 * синхронизации живёт в `PackageSyncState` слоя данных. Аптечку пачка держит ссылкой [MedKitRef].
 *
 * Состояний жизни у коробки нет: она либо есть, либо её нет; [status] говорит только о решении,
 * которое ещё не подтвердила полка, и о том, чем пока можно пользоваться. Пустой коробки не бывает — кончившаяся
 * (расход, утилизация, пересчёт в ноль) перестаёт существовать так же, как выброшенная, и переходы
 * отвечают на это [PackageAfter.Ended] с [PackageEnding] внутри. Истории у коробки нет: действия
 * меняют число или убирают коробку, записи «как было» не остаётся (PLAN D3, D7). Что от коробки
 * остаётся навсегда — [record]: за неё держатся приёмы (D6).
 *
 * Объект действителен в пределах транзакции, которая его прочитала: пачка на руках после
 * первого же приёма — пачка с прежним остатком, если её не перечитать.
 */
class Package(
    val id: Uuid,                 // придуман клиентом; он же серверный
    val medKit: MedKitRef,
    val facts: PackageFacts,
    val quantity: Quantity,
    val addedAt: Instant,         // для чужой пачки — момент ПЕРВОГО НАБЛЮДЕНИЯ
    val templateId: Uuid? = null, // из какой карточки справочника заполнено
    val claims: Claims? = null,   // null у неопубликованной аптечки
    val status: PackageStatus = PackageStatus.ACTIVE,
    val decidedBy: Uuid? = null   // команда, которая пометку поставила; снять её может только она
) {

    init {
        require(!quantity.isZero) { "пустой коробки не бывает: кончившаяся удаляется" }
        // Пометки без решения не бывает, а решения без пометки — тоже: пара выражает один факт,
        // и «кто её снимет» отвечается самой коробкой, а не поиском по очереди (PLAN E1).
        require((status == PackageStatus.ACTIVE) == (decidedBy == null)) {
            "пометка живёт вместе со своей командой: $status и $decidedBy друг другу не пара"
        }
        // Подсказка — это «сколько я обычно принимаю из ЭТОЙ пачки»: величина в чужой единице
        // не подставится в форму приёма и молча притворилась бы подходящей.
        val hint = facts.defaultIntakeAmount
        require(hint == null || hint.unit == quantity.unit) {
            "доза-подсказка измеряется той же единицей, что остаток пачки"
        }
    }

    val name: String get() = facts.name

    /**
     * Как пачку видит экран: состояние вместе с доступностью, посчитанной тем, кто читал очередь и
     * выделения, курсом, который её держит, и моментом последнего моего приёма из неё (PLAN D4,
     * E1). Величина — наружу уходит она, а не сущность.
     */
    fun projection(
        availability: PackageAvailability,
        hasUnconfirmedChanges: Boolean,
        holdingCourseId: Uuid?,
        lastUsedAt: Instant?
    ): PackageProjection =
        PackageProjection(
            id = id,
            ref = ref,
            medKit = medKit,
            facts = facts,
            quantity = quantity,
            addedAt = addedAt,
            templateId = templateId,
            claims = claims,
            availability = availability,
            hasUnconfirmedChanges = hasUnconfirmedChanges,
            holdingCourseId = holdingCourseId,
            lastUsedAt = lastUsedAt,
            status = status
        )

    /**
     * Вечная запись о коробке: снимок имени, единицы и формы идёт за живой пачкой, момент
     * появления — её собственный (PLAN D3). Хранение пишет запись вместе с пачкой.
     */
    val record: PackageRecord
        get() = PackageRecord(id = id, name = facts.name, unit = quantity.unit, form = facts.form, addedAt = addedAt)

    /** Как пачку видит чужой агрегат — курс, приём: ссылка на запись, без переходов. */
    val ref: PackageRef get() = record.ref

    /** Коробкой пользуются: из неё берут, её правят, переносят и ставят источником (PLAN E1). */
    val usable: Boolean get() = status.allowsUse

    /**
     * Что мешает унести коробку с её полки: ею уже не пользуются, или о самой полке принимается
     * решение — её публикация назвала серверу своё содержимое (PLAN E5).
     */
    fun refusesMoving(): MoveRefusal? = when {
        !usable -> MoveRefusal.UNUSABLE
        !medKit.status.allowsDecision -> MoveRefusal.ORIGIN_BUSY
        else -> null
    }

    enum class MoveRefusal { UNUSABLE, ORIGIN_BUSY }

    fun isExpiredOn(date: LocalDate): Boolean = facts.isExpiredOn(date)

    /** Срок, если к дню [date] он уже истёк; годна или срок неизвестен — `null`. */
    fun expiredOn(date: LocalDate): ExpiryDate? = facts.expiresOn?.takeIf { it.isExpiredOn(date) }

    fun expiresWithin(date: LocalDate, days: Long): Boolean = facts.expiresWithin(date, days)

    /**
     * Акт «беру из этой пачки»: дозу считают в её единице. Правило стоит в момент записи и
     * проверяется по пачке, какой её знает устройство сейчас; когда приём случился, называет
     * человек через [at]. Записанный факт этой проверке больше не подлежит (PLAN D6). Остаток
     * акт не меняет: списывает [consume] по состоянию в базе.
     */
    fun take(amount: Dose, at: Instant): Result<TakenDose> = when {
        // Решение выбросить или уйти уже принято: помеченной коробкой не пользуются (PLAN E1).
        !status.allowsUse -> Result.failure(IntakeRejected(IntakeRejected.Reason.PACKAGE_UNUSABLE))
        amount.unit != quantity.unit -> Result.failure(IntakeRejected(IntakeRejected.Reason.UNIT_MISMATCH))
        else -> Result.success(TakenDose(ref, amount, at))
    }

    companion object {

        /** Коробка, прочитанная по идентификатору снаружи: её может уже не быть (PLAN D3). */
        fun present(found: Package?): Result<Package> =
            found?.let { Result.success(it) } ?: Result.failure(IntakeRejected(IntakeRejected.Reason.PACKAGE_UNUSABLE))
    }

    /**
     * Тот же акт из коробки, чей остаток считается здесь же ([countedHere]): списать больше, чем в
     * ней есть, нечем. У коробки, которую знает сервер, истина по количеству — он, и нехватку
     * отвечает он (PLAN E3).
     */
    fun take(amount: Dose, at: Instant, countedHere: Boolean): Result<TakenDose> =
        take(amount, at).mapCatching { taken ->
            if (countedHere && !quantity.covers(amount)) throw IntakeRejected(IntakeRejected.Reason.INSUFFICIENT)
            taken
        }

    /**
     * Расход — приём, плановый или разовый. В минус не списывает (PLAN D5). Учётная запись о нём —
     * сам приём (PLAN D6, H6).
     */
    fun consume(amount: Dose): PackageAfter = after(quantity - amount.quantity)

    /**
     * Изменение остатка, отсчитанное от [from], переносится на [onto]: остаток становится
     * `onto + (quantity − from)`. Так сходятся два счёта одной коробки, которую унесли домой, пока
     * полка жила дальше: подтверждённое полкой и сделанное человеком дома после решения (PLAN E6).
     * Чужой расход, доставленный раньше, учтён, свой домашний — тоже; ушедшая в ноль коробка
     * кончается. Счёт в другой единице не сводится.
     */
    fun rebased(from: Quantity, onto: Quantity): PackageAfter {
        require(from.unit == quantity.unit && onto.unit == quantity.unit) { "счета одной коробки сводятся в её единице" }
        return after((quantity + onto).minusOrZero(from))
    }

    /**
     * Утилизация: выбросили [amount] — просроченное, испорченное. В минус пачка не уходит:
     * «выбросил больше, чем было» списывает остаток целиком, и коробка кончается.
     */
    fun dispose(amount: Quantity): PackageAfter {
        requireUsable()
        return after(quantity.minusOrZero(amount))
    }

    /**
     * Пересчёт: «пересчитал и увидел столько» — замена значения, а не дельта (E1). Единица та же:
     * она у пачки неизменна (C1). Ноль — коробки не осталось.
     */
    fun correctTo(actual: Quantity): PackageAfter {
        requireUsable()
        require(actual.unit == quantity.unit) { "единица пачки неизменна: пересчёт её не меняет" }
        return after(actual)
    }

    /**
     * Коробки больше нет — выбросили целиком, полка ответила «её нет», доступ утрачен. Чем это
     * вызвано, поведение не различает: остаётся запись, уходит строка (PLAN D3). Пометку конец не
     * спрашивает: он и есть доведение решения, которое её поставило (E1).
     */
    fun ended(): PackageEnding = PackageEnding(this)

    /** Заменяет описательные сведения целиком — и серверные поля, и локальные (PLAN D3). */
    fun describe(facts: PackageFacts): Package {
        requireUsable()
        return changed(facts = facts)
    }

    /**
     * Человек переставил коробку: перенос меняет только принадлежность, а что делать с бронями на
     * границе публикации, решает сценарий переноса (PLAN E6).
     *
     * Принимает ссылку на аптечку, а не её идентификатор: у вызывающего она на руках, а
     * подставить вместо неё чужой `Uuid` — пачки, формы, единицы — тогда становится нечем. Ссылка
     * несёт и пометку полки, поэтому «положить в убираемую» здесь же и отвергается: полка вот-вот
     * уйдёт, и коробка ушла бы с ней, ничего человеку не сказав (PLAN E1, E6).
     */
    fun moveTo(target: MedKitRef): Package {
        requireUsable()
        check(target.status.allowsUse) { "полка помечена (${target.status}): в неё не кладут до ответа" }
        return relocated(target)
    }

    /**
     * Коробка переехала **по ответу полки**, а не по решению человека: сервер подтвердил, что полку
     * унесли на другую, или отказал, и коробка вернулась туда, откуда приехала (PLAN E6).
     *
     * Поэтому пометок здесь не спрашивают — ни у коробки, ни у полки. Своё решение коробки может
     * ждать ответа и переезду не мешает, как не мешает оно и её концу; а полка, которую убирают, —
     * это ровно та полка, куда возвращают коробку, когда уборка не вышла.
     *
     * Пометку переход не снимает: её снимет ответ по той команде, которая её поставила (PLAN E1).
     */
    fun movedByAnswer(target: MedKitRef): Package = relocated(target)

    /** Переезд — на другую полку: ту же самую переездом не называют, кто бы его ни вёз. */
    private fun relocated(target: MedKitRef): Package {
        require(target != medKit) { "пачка уже лежит в этой аптечке" }
        return changed(medKit = target)
    }

    /**
     * Изменение ушло к полке командой [by] и ждёт её согласия. Пометка не мешает пользоваться
     * коробкой: полка ответит за каждое изменение по порядку (PLAN E1).
     */
    fun markChanging(by: Uuid): Package = marked(PackageStatus.CHANGING, by)

    /**
     * Человек решил выбросить коробку командой [by], а полка ещё не согласилась. Коробка видна, но
     * выведена из оборота; «ок» доведёт решение до конца ([ended]), сбой снимет пометку (E6).
     */
    fun markRemoving(by: Uuid): Package = marked(PackageStatus.REMOVING, by)

    /**
     * Человек уходит из общей полки командой [by], а коробка остаётся остальным. До ответа она
     * видна, но трогать её нельзя; «ок» доведёт решение до конца, сбой снимет пометку (PLAN E6).
     */
    fun markLost(by: Uuid): Package = marked(PackageStatus.LOST, by)

    /**
     * Команда [operation] закрыта. Пометку снимает **только та команда, которая её поставила**:
     * решение живёт, пока не отвечено оно само, а не первая попавшаяся команда коробки (PLAN E1).
     * Чужая закрытая команда оставляет коробку как есть — и это не сбой, а обычный порядок:
     * пометку выбрасывания не снимает ни расход, ни бронь, ни правка сведений.
     */
    fun settledBy(operation: Uuid): Package =
        if (decidedBy != operation) this else changed(status = PackageStatus.ACTIVE, decidedBy = null)

    private fun marked(status: PackageStatus, by: Uuid): Package {
        requireUsable()
        return changed(status = status, decidedBy = by)
    }

    private fun requireUsable() {
        check(status.allowsUse) { "коробка помечена ($status): ею не пользуются до ответа полки" }
    }

    /** Чем кончился переход: пустой коробки не бывает, поэтому ушедшая в ноль кончается. */
    private fun after(left: Quantity): PackageAfter =
        if (left.isZero) PackageAfter.Ended(PackageEnding(this))
        else PackageAfter.Left(changed(quantity = left))

    /**
     * Изменённый экземпляр; [id] и [addedAt] не меняются. Явный `claims = null` очищает брони,
     * непереданный аргумент их сохраняет.
     */
    private fun changed(
        medKit: MedKitRef = this.medKit,
        facts: PackageFacts = this.facts,
        quantity: Quantity = this.quantity,
        templateId: Uuid? = this.templateId,
        claims: Claims? = this.claims,
        status: PackageStatus = this.status,
        decidedBy: Uuid? = this.decidedBy
    ): Package = Package(
        id = id,
        medKit = medKit,
        facts = facts,
        quantity = quantity,
        addedAt = addedAt,
        templateId = templateId,
        claims = claims,
        status = status,
        decidedBy = decidedBy
    )

    /** Тождество — [id]. Пачка, из которой приняли таблетку, та же самая пачка. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Package && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Package(id=$id, name=${facts.name}, quantity=$quantity)"
}
