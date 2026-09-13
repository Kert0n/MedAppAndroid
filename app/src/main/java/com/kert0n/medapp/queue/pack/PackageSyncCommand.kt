package com.kert0n.medapp.queue.pack

import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.queue.ConflictPolicy
import com.kert0n.medapp.queue.Expected
import com.kert0n.medapp.queue.NotFoundPolicy
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.PreparedRequest
import com.kert0n.medapp.queue.StalePolicy
import com.kert0n.medapp.queue.SyncCommand
import kotlin.uuid.Uuid

/**
 * Что предстоит доставить серверу по одной упаковке: завести, описать, пересчитать, перенести,
 * удалить, списать, заявить или снять бронь. Команда описывает доставку, а не лечение,
 * поэтому живёт в данных; порядок применения задаёт `sequence` очереди. Виды вложены в корень —
 * это варианты одной команды (C1); общий маркер `SyncCommand` появится с `SyncOperation` (PR 4).
 */
sealed interface PackageSyncCommand : SyncCommand {

    val packageId: Uuid

    /**
     * В какой единице команда называет число, которое уедет без единицы: расход, бронь, пересчёт.
     * Сервер прочёл бы его в своей единице, поэтому по пачке в другой единице такая команда не
     * уходит (PLAN E3). `null` — голого числа нет: создание везёт единицу с собой, ноль пересчёта
     * становится удалением, у остальных числа нет вовсе.
     */
    val measuredIn: QuantityUnit?
        get() = when (this) {
            is Consume -> amount.unit
            is SetClaim -> amount.unit
            is CorrectStock -> actual.unit.takeUnless { actual.isZero }
            is Create, is Describe, is Move, is Delete, is Withdraw, is ReleaseClaim -> null
        }

    /**
     * Остаток после этой команды (PLAN E1); `null` — количество команда не меняет. Пересчёт
     * заменяет число, а не вычитает; удаление даёт ноль; расход больше остатка даёт ноль,
     * потому что нехватка — отказ сервера, и разбирается она снимком, а не числом.
     */
    fun appliedTo(amount: Quantity): Quantity? = when (this) {
        is Consume -> amount.minusOrZero(this.amount.quantity)
        is CorrectStock -> this.actual
        is Delete -> Quantity.zero(amount.unit)
        is Create, is Describe, is Move, is Withdraw, is SetClaim, is ReleaseClaim -> null
    }

    /**
     * Устаревшая версия: расход и бронь готовятся заново — дельту и абсолютное решение владельца
     * чужое изменение не отменяет; остальное чужая правка перекрывает, и человек смотрит заново.
     * Создание версии не везёт: 409 у него — «уже есть», и это не устаревание.
     */
    val onStale: StalePolicy
        get() = when (this) {
            is Consume, is SetClaim, is ReleaseClaim -> StalePolicy.REPREPARE
            is Create, is Describe, is CorrectStock, is Move, is Delete, is Withdraw -> StalePolicy.REFUSE
        }

    /**
     * 404: у команд над пачкой и переносом нет пачки или аптечки — доступа нет; у создания нет
     * **полки**, куда кладут, и это отказ, а не конец коробки; у правки брони нет своей брони —
     * заявить заново по свежему `mine`; у снятия брони и удаления нет того, что снимают или
     * удаляют, — уже так. Заявление брони 404 не отвечает иначе как пачкой.
     */
    val onNotFound: NotFoundPolicy
        get() = when (this) {
            // Создания нет полки, а не коробки: коробка у человека в руках, и кончать её нечем.
            is Create -> NotFoundPolicy.REFUSE
            is Describe, is Move, is Consume -> NotFoundPolicy.ACCESS_LOST
            is CorrectStock -> if (actual.isZero) NotFoundPolicy.APPLIED else NotFoundPolicy.ACCESS_LOST
            is SetClaim -> NotFoundPolicy.REPREPARE
            is ReleaseClaim, is Delete, is Withdraw -> NotFoundPolicy.APPLIED
        }

    /**
     * 409: у создания — «уже есть», у заявления брони — «уже заявлена». У расхода это тот же номер
     * с другим телом: переподготовка тела не меняет, значит такой ответ — дефект, а не состояние
     * сервера (PLAN E3). Версия сюда не относится: она отвечает 412.
     */
    val onConflict: ConflictPolicy
        get() = when (this) {
            is Create -> ConflictPolicy.EXISTS
            is SetClaim -> ConflictPolicy.REPREPARE
            is Consume, is Describe, is CorrectStock, is Move, is Delete, is Withdraw, is ReleaseClaim ->
                ConflictPolicy.REFUSE
        }

    /** 400: у расхода — больше остатка, единственный отказ по условию, что у него есть; у прочих — ввод. */
    val onInvalid: RefusalReason
        get() = if (this is Consume) RefusalReason.INSUFFICIENT else RefusalReason.INVALID

    /**
     * Форма успешного ответа по контракту операции (PLAN B4, B5): создание, правка и перенос
     * отвечают снимком; расход — снимком либо нулём байтов, когда пачка кончилась; бронь —
     * бронью без версии картины, и снимок читается следом; удаление и снятие брони — `204`.
     */
    override val expects: Expected
        get() = when (this) {
            is Create, is Describe, is Move -> Expected.SNAPSHOT
            is CorrectStock -> if (actual.isZero) Expected.NOTHING else Expected.SNAPSHOT
            is Consume -> Expected.SNAPSHOT_OR_GONE
            is SetClaim -> Expected.CLAIM
            is Delete, is Withdraw, is ReleaseClaim -> Expected.NOTHING
        }

    /**
     * Завести упаковку на сервере.
     *
     * Начальный остаток строго положителен — и в доменном сценарии, и в POST-DTO: пачка, которой
     * нет, не заводится (PLAN E2). Личные срок, заметка и цена в команде отсутствуют по типу:
     * уезжает [PackageSharedFacts], остальное остаётся на устройстве (PLAN C0).
     *
     * [fromMedKitId] — полка, с которой коробку принесли: местная, если её переложили на общую.
     * Не вышло — коробка возвращается туда, и потому команда помнит это место сама. `null` — её
     * никуда не перекладывали, общей стала полка под ней (публикация), и возвращать некуда.
     */
    data class Create(
        override val packageId: Uuid,
        val medKitId: Uuid,
        val quantity: Quantity,
        val facts: PackageSharedFacts,
        val fromMedKitId: Uuid? = null
    ) : PackageSyncCommand {
        init {
            require(!quantity.isZero) { "пачка заводится с положительным остатком" }
            require(fromMedKitId != medKitId) { "коробка приезжает с другой полки, а не с этой же" }
        }
    }

    /**
     * Изменить описание препарата на сервере.
     *
     * Хранит **и исходное, и желаемое** состояние: неизменённые поля не отправляются, очистка
     * текста становится `""`, а ограничение очистки формы не теряется (PLAN E2, D3). Одного
     * «желаемого» не хватило бы — по нему нельзя отличить «поле не трогали» от «поле очистили».
     *
     * Правку, не выходящую за границу публикации, отправлять незачем: `before == after` — это
     * команда, которой нечего делать на проводе.
     */
    data class Describe(
        override val packageId: Uuid,
        val before: PackageSharedFacts,
        val after: PackageSharedFacts
    ) : PackageSyncCommand {
        init {
            require(before != after) { "описание не изменилось: отправлять нечего" }
        }
    }

    /**
     * Пересчитали и увидели столько.
     *
     * **Абсолютное значение, а не дельта** (PLAN E1): проекция заменяет остаток, а не вычитает.
     * Ноль допустим и становится `DELETE` при подготовке запроса — это форма провода, а не смысл команды (B6).
     */
    data class CorrectStock(
        override val packageId: Uuid,
        val actual: Quantity
    ) : PackageSyncCommand

    /** Перенести упаковку в другую аптечку. */
    data class Move(
        override val packageId: Uuid,
        val targetMedKitId: Uuid
    ) : PackageSyncCommand

    /**
     * Удалить упаковку на сервере.
     *
     * Строка живёт до ответа: запрос готовится по её версии, а в проекции остатка уже ноль
     * (PLAN E1). «Пачки нет» в ответе уносит строку со всеми частями; приёмы держатся за запись о
     * ней, а не за строку (PLAN D3, D6).
     */
    data class Delete(override val packageId: Uuid) : PackageSyncCommand

    /**
     * Коробку унесли с общей полки [fromMedKitId] домой, на местную (PLAN E6).
     *
     * На проводе это то же `DELETE` с версией, что и у [Delete], но смысл другой — поэтому и вид
     * другой: остаток не меняется, «пачки нет» — желаемое, а не конец коробки, а отказ возвращает
     * её на полку, откуда взяли. Для остальных коробка исчезает, и сервер о ней больше не знает;
     * публиковать местную полку незачем.
     *
     * [carried] — остаток, с которым коробку унесли. Когда полка ответит, её подтверждённое число
     * и сделанное дома после решения сводятся от него (`Package.rebased`).
     */
    data class Withdraw(
        override val packageId: Uuid,
        val fromMedKitId: Uuid,
        val carried: Quantity
    ) : PackageSyncCommand

    /**
     * Списать фактически принятое.
     *
     * [intakeId] отдельным полем: **команда приёма не поглощает следующий факт**, у каждого
     * подтверждения свой идентификатор, и повтор отправки не превращается во второе списание
     * (PLAN E2).
     *
     * [claimAfter] — новая **абсолютная** бронь после расхода, и три её значения означают три
     * разных действия (PLAN E2):
     * - `null` — внеплановый расход, брони не касается;
     * - положительное — курсовой расход с новым объёмом брони;
     * - ноль — курсовой расход без блока брони, а снятие уезжает зависимым [ReleaseClaim].
     *
     * Величина брони не всегда уменьшается на физический расход: при частичной или увеличенной
     * дозе она пересчитывается по правилам D5, поэтому здесь два независимых числа, а не одно.
     */
    data class Consume(
        override val packageId: Uuid,
        val amount: Dose,
        val intakeId: Uuid,
        val claimAfter: Quantity? = null
    ) : PackageSyncCommand {
        init {
            require(claimAfter == null || claimAfter.unit == amount.unit) {
                "бронь измеряется той же единицей, что расход"
            }
        }

        /**
         * Применился ли этот расход, судя по броням: `sync` пишет расход и бронь одной
         * транзакцией, и своя бронь, равная заявленной после расхода и не равной той, что была
         * до запроса, — след применения. Так потерянный ответ, за которым пришёл отказ по
         * версии, отличается от расхода, который сервер не видел (решение владельца, PLAN E3).
         * Без блока брони или при броне, которую расход не менял, судить нечем — `false`.
         */
        /**
         * Опустошил бы этот расход пачку: доза не меньше подтверждённого остатка, по которому
         * готовился запрос. Пачка, списанная до нуля, сервером уничтожается, и повтор такого
         * расхода отвечает 404 (PLAN B4): это наш же расход, дошедший до нуля, а не утрата доступа.
         */
        fun emptiedBy(prepared: PreparedRequest): Boolean {
            val before = prepared.quantityBefore ?: return false
            return amount.quantity.amount >= before.amount
        }

        fun provenAppliedBy(snapshot: PackageSnapshot, prepared: PreparedRequest): Boolean {
            val wanted = claimAfter?.takeUnless { it.isZero } ?: return false
            val before = prepared.mineBefore ?: return false
            if (before.amount.compareTo(wanted.amount) == 0) return false
            val mine = snapshot.pack.claims?.mine ?: return false
            return mine.compareTo(wanted.amount) == 0
        }
    }

    /**
     * Заявить бронь на упаковку — серверное представление невыбранного выделения источника курса
     * (PLAN D5).
     *
     * Величина **абсолютная**: сервер хранит одну бронь на пару «человек и упаковка», и целевой
     * объём равен `allocatedDoses × dose`. Ноль здесь не пишут: снятие — это [ReleaseClaim], и на
     * проводе у него другая операция.
     */
    data class SetClaim(
        override val packageId: Uuid,
        val amount: Quantity
    ) : PackageSyncCommand {
        init {
            require(!amount.isZero) { "нулевая бронь — это снятие брони, у него свой вид" }
        }
    }

    /**
     * Снять свою бронь с упаковки: источник исчерпан, курс завершён или отменён (PLAN D5).
     *
     * Если пачка уже уничтожена, каскад снял бронь до нас, и зависимое снятие закрывается по
     * проверенному отсутствию, а не считается неудачей (PLAN E2).
     */
    data class ReleaseClaim(override val packageId: Uuid) : PackageSyncCommand
}
