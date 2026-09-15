package com.kert0n.medapp.presentation.course

/**
 * Форма лечения в том виде, в каком её держит экран: строками, как человек напечатал
 * (PLAN H3 №15). Обязательно здесь одно — название: «записал у врача, куплю завтра» — законный
 * черновик с одной заметкой (D5).
 */
data class CourseFormPresentationDTO(
    val title: String = "",
    val note: String = ""
)
