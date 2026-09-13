package com.kert0n.medapp.storage.course

import com.kert0n.medapp.domain.course.Course

/**
 * Курс до и после того, как он последовал за коробкой (PLAN D5): по паре вызывающий ставит
 * брони разницей. Обе сущности действительны в транзакции, которая их прочитала, — как `find*`.
 */
class CourseFollowed(val before: Course, val after: Course)
