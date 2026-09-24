package com.kert0n.medapp.domain.account

import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid

/**
 * Учётка устройства: логин и пароль, которые устройство **придумывает само** и записывает раньше,
 * чем о них узнаёт сервер (PLAN B1, G2). Поэтому потерянный ответ регистрации ничего не теряет:
 * повтор идёт теми же данными, а сервер отвечает «такая уже есть». Пароль секретен и в `toString`
 * не показывается.
 *
 * Пределы пароля — правило регистрации, и держит их её сетевая форма: учётка, заведённая когда-то
 * с другим паролем, остаётся рабочей, и читать её это не мешает.
 */
data class AccountCredentials(val login: Uuid, val password: String) {

    init {
        require(password.isNotEmpty()) { "учётка без пароля ничего не открывает" }
    }

    override fun toString(): String = "AccountCredentials(login=$login, password=***)"

    companion object {

        /**
         * Новая учётка: случайный логин и 32 случайных байта в Base64 URL — сорок три знака,
         * которые регистрация принимает. Случайность берётся у платформы, а не у `Random`: это
         * секрет, а не значение для теста.
         */
        fun random(): AccountCredentials {
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return AccountCredentials(Uuid.random(), Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
        }
    }
}
