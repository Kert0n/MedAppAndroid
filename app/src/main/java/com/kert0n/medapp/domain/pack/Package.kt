package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.domain.intake.TakenDose
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.stock.StockMovement
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
 * отвечают на это [PackageAfter.Ended] с [PackageEnding] внутри: у конца есть след, объясняющий,
 * куда делся остаток, и выбирает его переход, а не тот, кто записывает (PLAN D3, H6). Что от
 * коробки остаётся навсегда — [record]: за неё держатся приёмы и движения (D6, D7).
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
    val status: PackageStatus = PackageStatus.ACTIVE
) {

    init {
        require(!quantity.isZero) { "пустой коробки не бывает: кончившаяся удаляется" }
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
     * выделения (PLAN D4, E1). Величина — наружу уходит она, а не сущность.
     */
    fun projection(availability: PackageAvailability, hasUnconfirmedChanges: Boolean): PackageProjection =
        PackageProjection(
            id = id,
            medKit = medKit,
            facts = facts,
            quantity = quantity,
            addedAt = addedAt,
            templateId = templateId,
            claims = claims,
            availability = availability,
            hasUnconfirmedChanges = hasUnconfirmedChanges,
            status = status
        )

    /**
     * Вечная запись о коробке: снимок имени, единицы и формы идёт за живой пачкой, момент
     * появления — её собственный (PLAN D3). Хранение пишет запись вместе с пачкой.
     */
    val record: PackageRecord
        get() = PackageRecord(id = id, name = facts.name, unit = quantity.unit, form = facts.form, addedAt = addedAt)

    /** Как пачку видит чужой агрегат — курс, приём, движение: ссылка на запись, без переходов. */
    val ref: PackageRef get() = record.ref

    fun isExpiredOn(date: LocalDate): Boolean = facts.isExpiredOn(date)

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

    /**
     * Расход — приём, плановый или разовый. В минус не списывает (PLAN D5). Следа в истории
     * расход не оставляет: приём и есть учётная запись о нём (PLAN D7, H6).
     */
    fun consume(amount: Dose): PackageAfter = after(quantity - amount.quantity, trace = null)

    /**
     * Изменение остатка, отсчитанное от [from], переносится на [onto]: остаток становится
     * `onto + (quantity − from)`. Так сходятся два счёта одной коробки, которую унесли домой, пока
     * полка жила дальше: подтверждённое полкой и сделанное человеком дома после решения (PLAN E6).
     * Чужой расход, доставленный раньше, учтён, свой домашний — тоже. Следа нет: оба изменения уже
     * объяснены своими приёмами; ушедшая в ноль коробка кончается. Счёт в другой единице не сводится.
     */
    fun rebased(from: Quantity, onto: Quantity): PackageAfter {
        require(from.unit == quantity.unit && onto.unit == quantity.unit) { "счета одной коробки сводятся в её единице" }
        return after((quantity + onto).minusOrZero(from), trace = null)
    }

    /**
     * Утилизация: выбросили [amount] — просроченное, испорченное. В минус пачка не уходит, поэтому
     * «выбросил больше, чем было» списывает остаток целиком, а в историю идёт то, что **ушло на
     * самом деле** — разница остатков до и после (PLAN D7).
     */
    fun dispose(
        amount: Quantity,
        movementId: Uuid,
        at: Instant,
        reason: StockMovement.Disposal.Reason = StockMovement.Disposal.Reason.OTHER,
        note: String? = null
    ): PackageAfter {
        requireUsable()
        return disposed(amount, movementId, at, reason, note)
    }

    /**
     * Пересчёт: «пересчитал и увидел столько» — замена значения, а не дельта (E1). Единица та же:
     * смена единицы — отдельный сценарий (D3).
     */
    fun correctTo(actual: Quantity, movementId: Uuid, at: Instant, note: String? = null): PackageAfter {
        requireUsable()
        return corrected(actual, movementId, at, note)
    }

    /**
     * Человек выбросил коробку целиком (ТЗ 4.1.1.3.5). Это утилизация всего остатка, и объясняется
     * она так же: без её следа «истрачено за период» не сошлось бы — остаток исчез бы, никем не
     * принятый и ничем не объяснённый (PLAN H6).
     */
    fun thrownOut(
        movementId: Uuid,
        at: Instant,
        reason: StockMovement.Disposal.Reason = StockMovement.Disposal.Reason.OTHER,
        note: String? = null
    ): PackageEnding = when (val after = disposed(quantity, movementId, at, reason, note)) {
        is PackageAfter.Ended -> after.ending
        is PackageAfter.Left -> error("выброшенная целиком коробка не остаётся: ${after.pkg}")
    }

    /**
     * Пересчитали и увидели ноль: коробки не осталось, а «было столько» объясняет пересчёт — без
     * него остаток пропал бы из учёта без объяснения (PLAN D7, H6).
     */
    fun recountedToZero(movementId: Uuid, at: Instant, note: String? = null): PackageEnding =
        when (val after = corrected(Quantity.zero(quantity.unit), movementId, at, note)) {
            is PackageAfter.Ended -> after.ending
            is PackageAfter.Left -> error("пересчитанная в ноль коробка не остаётся: ${after.pkg}")
        }

    /**
     * Пачки нет на сервере, и нет по нашей же причине — мы сами её туда и отправили удалять либо
     * израсходовали до конца. О количестве это не говорит ничего, поэтому следа нет (PLAN D7).
     */
    fun goneOnServer(): PackageEnding = PackageEnding(this, trace = null)

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
        require(target != medKit) { "пачка уже лежит в этой аптечке" }
        check(target.status.allowsUse) { "полка помечена (${target.status}): в неё не кладут до ответа" }
        return changed(medKit = target)
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
    fun movedByAnswer(target: MedKitRef): Package {
        require(target != medKit) { "пачка уже лежит в этой аптечке" }
        return changed(medKit = target)
    }

    /**
     * Доступ утрачен: вышли из аптечки, её унесли или удалили. Коробка цела, но не у нас, и
     * последний виденный остаток уходит из учёта записью в историю (PLAN D7); самой пачки после
     * этого не остаётся. Тождество записи называет вызывающий: повтор не заводит вторую.
     */
    fun lost(movementId: Uuid, at: Instant): PackageEnding =
        PackageEnding(this, StockMovement.AccessLoss(movementId, ref, quantity, observedAt = at))

    /**
     * Сервер назвал остаток [server], а наши установленные изменения объясняют [explained] — наш
     * подтверждённый остаток с тем, что сделали мы сами. Необъяснённая разница — чужое изменение, и
     * причина его не выдумывается (PLAN D7): иначе наш расход попал бы в историю дважды — приёмом и
     * разницей. Нет разницы — нет и записи, поэтому повтор того же снимка движений не плодит. В
     * разных единицах разницы не существует: сосед сменил единицу, и сравнивать нечего.
     */
    fun changedElsewhere(server: Quantity, explained: Quantity, movementId: Uuid, at: Instant): StockMovement.RemoteChange? {
        if (server.unit != explained.unit || server == explained) return null
        return StockMovement.RemoteChange(movementId, ref, server.amount - explained.amount, server.unit, observedAt = at)
    }

    /**
     * Изменение ушло к полке и ждёт её согласия. Пометка не мешает пользоваться коробкой: полка
     * ответит за каждое изменение по порядку (PLAN E1).
     */
    fun markChanging(): Package {
        requireUsable()
        return changed(status = PackageStatus.CHANGING)
    }

    /**
     * Человек решил выбросить коробку, а полка ещё не согласилась. Коробка видна, но выведена из
     * оборота; «ок» доведёт её до конца ([thrownOut]), сбой снимет пометку ([settled]) (PLAN E6).
     */
    fun markRemoving(): Package {
        requireUsable()
        return changed(status = PackageStatus.REMOVING)
    }

    /**
     * Человек уходит из общей полки, а коробка остаётся остальным. До ответа она видна, но трогать
     * её нельзя; «ок» доведёт её до конца ([lost]), сбой снимет пометку ([settled]) (PLAN E6).
     */
    fun markLost(): Package {
        requireUsable()
        return changed(status = PackageStatus.LOST)
    }

    /** Полка ответила, а решать больше нечего: пометка снимается, коробка снова обычная. */
    fun settled(): Package = changed(status = PackageStatus.ACTIVE)

    private fun requireUsable() {
        check(status.allowsUse) { "коробка помечена ($status): ею не пользуются до ответа полки" }
    }

    /** Утилизация без проверки пометки — шаг и пользования, и конца. */
    private fun disposed(
        amount: Quantity,
        movementId: Uuid,
        at: Instant,
        reason: StockMovement.Disposal.Reason,
        note: String?
    ): PackageAfter {
        val left = quantity.minusOrZero(amount)
        return after(left, StockMovement.Disposal(movementId, ref, quantity - left, reason, at, at, note))
    }

    /** Пересчёт без проверки пометки — шаг и пользования, и конца. */
    private fun corrected(actual: Quantity, movementId: Uuid, at: Instant, note: String?): PackageAfter {
        require(actual.unit == quantity.unit) {
            "пересчёт не меняет единицу: это отдельный сценарий"
        }
        return after(actual, StockMovement.Recount(movementId, ref, quantity, actual, at, at, note))
    }

    /**
     * Чем кончился переход: пустой коробки не бывает, поэтому ушедшая в ноль кончается, а [trace]
     * объясняет, куда делся её остаток. У оставшейся след тот же — он о том, что произошло, а не о
     * том, чем это кончилось.
     */
    private fun after(left: Quantity, trace: StockMovement?): PackageAfter =
        if (left.isZero) PackageAfter.Ended(PackageEnding(this, trace))
        else PackageAfter.Left(changed(quantity = left), trace)

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
        status: PackageStatus = this.status
    ): Package = Package(
        id = id,
        medKit = medKit,
        facts = facts,
        quantity = quantity,
        addedAt = addedAt,
        templateId = templateId,
        claims = claims,
        status = status
    )

    /** Тождество — [id]. Пачка, из которой приняли таблетку, та же самая пачка. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Package && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Package(id=$id, name=${facts.name}, quantity=$quantity)"
}
