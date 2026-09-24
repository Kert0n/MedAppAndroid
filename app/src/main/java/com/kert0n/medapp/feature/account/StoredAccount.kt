package com.kert0n.medapp.feature.account

import com.kert0n.medapp.domain.account.AccountCredentials

/**
 * Что устройство знает о своей учётке. Случаев четыре, потому что поведение у них разное: нет
 * учётки — придумать и зарегистрировать; **придумана, но сервер её не подтвердил** — повторить
 * регистрацию **теми же** данными; подтверждена — работать; **нечитаема** — спросить человека, а
 * не заводить молча новую поверх локальных данных (PLAN G2).
 *
 * Неподтверждённая учётка появилась вместе с тем, что данные придумывает устройство: они ложатся
 * на устройство раньше запроса, и до ответа сервера неизвестно, знает ли он о них.
 */
sealed interface StoredAccount {

    data object Absent : StoredAccount

    data class Pending(val credentials: AccountCredentials) : StoredAccount

    data class Present(val credentials: AccountCredentials) : StoredAccount

    data object Unreadable : StoredAccount
}
