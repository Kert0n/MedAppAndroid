package com.kert0n.medapp.presentation.bootstrap

import com.kert0n.medapp.feature.bootstrap.AppStart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Состояние начала для экрана. Настройка начинается сама при первом появлении и повторяется по
 * нажатию человека; пока предыдущая попытка идёт, новая не начинается — иначе «повторить»,
 * нажатое трижды, завело бы три захода на регистрацию.
 *
 * Живёт в ViewModel, а не в `MedApp.onCreate`: у настройки есть повтор, и её исход человек видит
 * на экране, а не в логе. К тому же приложение под инструментами подменяется, и настройка из
 * `onCreate` была бы непроверяемой.
 */
@HiltViewModel
class AppStartViewModel @Inject constructor(private val start: AppStart) : ViewModel() {

    private val _state = MutableStateFlow<AppStartState>(AppStartState.Checking)

    val state: StateFlow<AppStartState> = _state.asStateFlow()

    private var attempt: Job? = null

    init {
        begin()
    }

    fun retry() = begin()

    private fun begin() {
        if (attempt?.isActive == true) return
        attempt = viewModelScope.launch {
            _state.value = AppStartState.Checking
            _state.value = start.begin().toAppStartState()
        }
    }
}
