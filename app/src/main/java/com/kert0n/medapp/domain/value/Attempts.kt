package com.kert0n.medapp.domain.value

/**
 * Сколько раз мы уже пробовали — и только это: **вход задержки повтора**, а не мера чего-либо.
 * Отрицательным не бывает.
 *
 * Своим типом это стало потому, что пробуют у нас двое: операция очереди, которой не ответил
 * сервер (PLAN E3), и обязательство, которое не удалось показать (D8). Одинаковый `require` в двух
 * местах одного слоя значит, что у правила нет своего типа, — так уже было с числом доз и
 * редакцией.
 */
@JvmInline
value class Attempts(val count: Int) : Comparable<Attempts> {

    init {
        require(count >= 0) { "число попыток не бывает отрицательным: $count" }
    }

    val isNone: Boolean get() = count == 0

    /** Ещё одна попытка. */
    fun next(): Attempts = Attempts(count + 1)

    override fun compareTo(other: Attempts): Int = count.compareTo(other.count)

    override fun toString(): String = count.toString()

    companion object {
        val none = Attempts(0)
    }
}
