package com.kert0n.medapp.presentation.medkit

/**
 * Что напечатано в форме аптечки. Строки, а не величины: пока человек печатает, «Домашняя » с
 * пробелом на конце и пустое место хранения — законные промежуточные состояния, и домен о них
 * знать не должен (PLAN H1).
 */
data class MedKitFormPresentationDTO(val name: String = "", val location: String = "")

/** Название и место хранения, уже проверенные: их и принимает сценарий. */
data class MedKitDescription(val name: String, val location: String?)
