package com.kert0n.medapp.domain.value

import kotlin.uuid.Uuid

/**
 * Снимок словаря старее того, кто назвал единицу или форму. Не «такого нет»: словарь только
 * растёт, и промах лечится чтением с сервера, а без связи — это задержка с названной причиной.
 * Исключение, а не `null`, потому что промах случается глубоко в разборе, а решает его тот, кто
 * снимок держит и дочитывает.
 */
class VocabularyMiss(val kind: Kind, val id: Uuid) : IllegalStateException("$kind $id не в снимке словаря") {

    enum class Kind { UNIT, FORM }
}
