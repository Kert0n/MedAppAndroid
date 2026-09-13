package com.kert0n.medapp.storage.course

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.Revision

/**
 * Пересчитанные выделения курса и редакция, из которой они посчитаны (PLAN D5, F5).
 *
 * Редакция едет вместе с ними, потому что запись условна: план, закрытый или пересчитанный между
 * чтением и записью, не возвращается и не переписывается результатом, посчитанным из прошлого
 * состава. Расписание и доза при этом не трогаются — их меняет изменение лечения, а пересчёт меняет
 * только обеспечение.
 */
data class CourseReallocation(val course: Course, val expected: Revision) {
    init {
        require(expected <= course.revision) {
            "пересчёт идёт вперёд: из редакции $expected нельзя получить ${course.revision}"
        }
    }
}
