package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.medkit.MedKitRef
import kotlin.uuid.Uuid

/**
 * Как ложится переход вещи для общего реестра (PLAN E1–E6) — ответ очереди сценарию. Сценарий по
 * нему ветвится и пишет, а спрашивать, отвечает ли полка серверу, ему не нужно.
 */
sealed interface Laying {

    /** Поручения, которые встают в очередь той же транзакцией, что и переход. */
    val errands: List<QueuedCommand>

    /**
     * Переход ложится сразу. Поручения, если они есть, рассказывают о нём полке, которая к серверу
     * только едет, — вещь их не ждёт.
     */
    class Now(override val errands: List<QueuedCommand> = emptyList()) : Laying

    /**
     * Решение о вещи ждёт сервера: она помечена первым поручением ([by]), и до его ответа нового
     * решения о ней не принимают.
     */
    class Awaiting(override val errands: List<QueuedCommand>) : Laying {
        init {
            require(errands.isNotEmpty()) { "ждать нечего, если поручений нет" }
        }

        val by: Uuid get() = errands.first().id
    }

    /** Реестр такого перехода не принимает: сказать ему это нечем. */
    data object Refused : Laying {
        override val errands: List<QueuedCommand> get() = emptyList()
    }
}

/** Где ляжет расход приёма: сразу, если сервер коробки не знает, или поручением, если истина — он (E3, E6). */
enum class Spending { LOCAL, REMOTE }

/** Как переносится коробка между полками, и что об этом сказать реестру (PLAN E1, E6). */
sealed interface Carrying {

    /** С общей полки на свою: место меняется сразу, сервер забывает коробку поручением. */
    class Home(val laying: Laying.Awaiting) : Carrying

    /** Между общими: переставляет сервер, и до ответа коробка остаётся там, где лежит. */
    class ByServer(val laying: Laying.Awaiting) : Carrying

    /** Своя на общую: место меняется сразу, сервер узнаёт коробку её созданием. */
    data object Announced : Carrying

    /** Между своими: серверу сказать нечего. */
    data object Local : Carrying
}

/** Как убирается полка целиком, и что об этом сказать реестру (PLAN E1, E6). */
sealed interface Clearing {

    /** С общей полки содержимое уносят на свою [target]: каждая коробка забывается сервером, потом полка. */
    class Home(val target: MedKitRef) : Clearing

    /** Общую полку убирает сервер: содержимое ждёт его ответа под этим поручением. */
    class ByServer(val laying: Laying.Awaiting) : Clearing

    /** Своя полка: серверу сказать нечего. */
    data object Local : Clearing
}
