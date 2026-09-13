package com.kert0n.medapp.domain.report

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseProgress

/**
 * Идущее лечение вместе с тем, что по нему уже отвечено: из этой пары отчёты узнают, какие дозы
 * ещё впереди и на какие пункты они лягут (PLAN D5, H6).
 */
class CourseInProgress(val course: Course, val progress: CourseProgress)
