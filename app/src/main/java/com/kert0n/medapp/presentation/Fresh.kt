package com.kert0n.medapp.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Что экран прочитал из базы после перечитывания у сервера (PLAN E4). Случая два, потому что
 * экран делает в них разное: при [Waiting] — ждёт, при [Read] — показывает и даёт решать.
 */
sealed interface Fresh<out T> {

    /** Сервер ещё не ответил — или ответил, а база после ответа ещё не прочитана. */
    data object Waiting : Fresh<Nothing>

    data class Read<T>(val value: T) : Fresh<T>
}

/**
 * Чтение базы, **начатое после** перечитывания.
 *
 * Дверь `Freshening` возвращается, когда ответ уложен, но поток базы, открытый раньше, узнаёт
 * об этом позже — своим повторным запросом. Экран, снявший ожидание по возврату двери, успел бы
 * показать прежнее число с живыми кнопками, и человек решил бы по нему. Поэтому поток
 * открывается после двери: первое его значение прочитано уже после ответа, и ожидание кончается
 * именно на нём.
 *
 * Читает, пока жива область: у экрана это его `viewModelScope`, и дверь зовётся один раз на
 * экземпляр — поворот экрана её не повторяет.
 */
fun <T> CoroutineScope.readAfter(freshen: suspend () -> Unit, observe: () -> Flow<T>): StateFlow<Fresh<T>> {
    val reading = MutableStateFlow<Fresh<T>>(Fresh.Waiting)
    launch {
        freshen()
        observe().collect { reading.value = Fresh.Read(it) }
    }
    return reading.asStateFlow()
}
