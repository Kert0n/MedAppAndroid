package com.kert0n.medapp.presentation.medkit

/**
 * Полка в том виде, в каком её держит форма: строками, как человек напечатал (PLAN H1). Пока он
 * печатает, в месте хранения лежит пустая строка, и местом это ещё не является — «не указано» и
 * «указано пустым» для домена одно и то же, а для формы нет.
 *
 * Обязательно одно название (H3 №3).
 */
data class MedKitFormPresentationDTO(val name: String = "", val location: String = "")

/**
 * Название и место, прошедшие разбор: то, что форма отдаёт сценарию. Отдельный тип, а не та же
 * форма: у неё поля — сырой ввод, а здесь место хранения уже `null`, когда его не указали.
 */
data class MedKitDescription(val name: String, val location: String?)

/**
 * Почему форма полки не записалась. Случая два, и человек делает в них разное: названное поле он
 * правит, а помеченную полку — ждёт. Текст по причине берёт экран из `R.string.*`: маппер не
 * решает, на каком языке говорит приложение.
 */
sealed interface MedKitFormError {

    /** Какое поле подсветить; `null` — беда не в поле. */
    val field: Field?

    enum class Field { NAME, LOCATION }

    /** Ввод: человек правит названное поле, и отказ снимается его же правкой. */
    enum class Input(override val field: Field) : MedKitFormError {
        NAME_EMPTY(Field.NAME),
        NAME_TOO_LONG(Field.NAME),
        LOCATION_TOO_LONG(Field.LOCATION)
    }

    /** Полка ждёт ответа сервера на свою уборку: поле тут ни при чём, править нечего. */
    data object Busy : MedKitFormError {
        override val field: Field? get() = null
    }
}
