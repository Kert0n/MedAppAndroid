package com.kert0n.medapp.ui

import com.kert0n.medapp.presentation.value.QuantityPresentationDTO

/**
 * Количество словами: «2 таблетка». Живёт общим местом, а не при экране курса: так количество
 * пишут и карточка коробки, и строка дня, — и написание у них обязано быть одним.
 */
internal fun QuantityPresentationDTO.words(): String = "$amount ${unit.name}"
