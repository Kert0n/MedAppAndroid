package com.kert0n.medapp.queue

import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand

/**
 * Общий маркер команды очереди. Обычный интерфейс, а не `sealed`: корней два, по понятиям —
 * `PackageSyncCommand` и `MedKitSyncCommand`, — потому что деление по понятиям важнее одного
 * корня на все команды, а Kotlin не закрывает иерархию через пакеты (PLAN E2).
 *
 * Цена решения названа: исчерпывающего `when` по всем командам сразу у общего диспетчера нет.
 * Зато он есть у обработчика каждого понятия, а конвертер хранения подкреплён круговым тестом
 * по всем одиннадцати видам.
 */
interface SyncCommand {

    /** Какой формы успешный ответ ждёт эта команда; проверяет транспорт (PLAN B5). */
    val expects: Expected
}

/**
 * Корней команд два, и оба известны каждому диспетчеру. Третий означает, что маркер надели на
 * новое понятие и забыли про остальных — исчерпывающего `when` у маркера нет (PLAN E2).
 */
fun SyncCommand.unknownRoot(): Nothing = error("команда неизвестного корня: ${this::class.simpleName}")

/**
 * Объявляет ли команда вещь серверу впервые. Таких две — создание коробки и публикация полки, — и
 * только они едут к полке, которую сервер ещё не завёл: всё остальное меняет уже известное, а
 * менять у сервера нечего, пока он о вещи не слышал (PLAN E5).
 */
val SyncCommand.announcesToServer: Boolean
    get() = this is PackageSyncCommand.Create || this is MedKitSyncCommand.Publish
