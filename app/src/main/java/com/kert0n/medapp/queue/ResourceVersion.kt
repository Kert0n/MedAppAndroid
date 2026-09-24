package com.kert0n.medapp.queue

/**
 * Версия ресурса на сервере — токен предусловия, который устройство **не толкует**, а
 * возвращает серверу вместе с поручением (PLAN B3). Это знание очереди о реестре: подставить
 * версию броней вместо версии пачки видно по имени поля, а «не бывает отрицательной» стоит один
 * раз. Как версия выглядит на проводе, решает сеть.
 */
@JvmInline
value class ResourceVersion(val number: Long) : Comparable<ResourceVersion> {

    init {
        require(number >= 0) { "версия ресурса не бывает отрицательной: $number" }
    }

    override fun compareTo(other: ResourceVersion): Int = number.compareTo(other.number)

    override fun toString(): String = "версия $number"
}
