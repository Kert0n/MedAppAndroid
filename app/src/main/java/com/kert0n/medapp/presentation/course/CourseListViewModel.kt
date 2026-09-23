package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.presentation.stateInScreen
import com.kert0n.medapp.presentation.ScreenReading
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.storage.course.CourseStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Список курсов (PLAN H3 №13): идущие — первыми и с нехваткой, названной словами; черновики;
 * законченные. Три чтения — записи, черновики, обеспечение — сходятся здесь, потому что вопрос
 * у человека один: «что я лечу и всё ли для этого есть».
 *
 * Отказа у экрана нет: чтения местные. Пока первое значение не пришло — [ScreenState.Loading].
 * Порядок внутри полок — тот, что отдаёт база; идущие и законченные — по записи, черновики — по
 * черновику: экран не сортирует, а показывает.
 */
@HiltViewModel
class CourseListViewModel @Inject constructor(courses: CourseStorageRepository) : ViewModel() {

    /** Что экран читает из базы; не прочиталось — говорит об этом и предлагает повторить. */
    val reading = ScreenReading()

    val state: StateFlow<ScreenState<CourseListPresentationDTO>> = combine(
        courses.observeRecords(),
        courses.observeDrafts(),
        courses.observeCoverages()
    ) { records, drafts, coverages ->
        val (open, closed) = records.partition { it.isOpen }
        ScreenState.Ready(
            CourseListPresentationDTO(
                running = open.map { it.toPresentationDTO(coverages[it.id]) },
                drafts = drafts.map { it.toPresentationDTO() },
                finished = closed.sortedByDescending { it.closedAt }.map { it.toPresentationDTO(coverage = null) }
            )
        )
    }.stateInScreen(viewModelScope, reading, ScreenState.Loading)
}
