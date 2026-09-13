package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.storage.course.CourseStorageRepository
import kotlin.uuid.Uuid

/**
 * План идущего эпизода. У открытой записи план есть всегда: они рождаются и кончаются одной
 * транзакцией (PLAN F5), — и спрашивают его сценарии, уже узнавшие, что эпизод открыт.
 */
internal suspend fun CourseStorageRepository.openPlan(id: Uuid): Course =
    checkNotNull(findPlan(id)) { "у идущего эпизода есть план" }
