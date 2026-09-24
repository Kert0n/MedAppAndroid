package com.kert0n.medapp.presentation.operation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.notification.NotificationReadiness
import com.kert0n.medapp.feature.operation.OperationReadings
import com.kert0n.medapp.platform.settings.AppLanguages
import com.kert0n.medapp.platform.settings.DevicePermissions
import com.kert0n.medapp.presentation.ScreenReading
import com.kert0n.medapp.presentation.settings.LanguageChoice
import com.kert0n.medapp.presentation.settings.permissionsNow
import com.kert0n.medapp.presentation.settings.toChoice
import com.kert0n.medapp.presentation.stateInScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Место «Опции» (PLAN H3): у каждой строки своё «есть ли о чём беспокоиться» — очередь ждёт,
 * разрешение выключено, какой выбран язык, — и собирает их один владелец в одно состояние.
 * Разрешения и язык хранит система, поэтому они перечитываются при возвращении ([refresh]).
 */
@HiltViewModel
class OptionsViewModel @Inject constructor(
    operations: OperationReadings,
    private val permissions: DevicePermissions,
    private val readiness: NotificationReadiness,
    private val languages: AppLanguages
) : ViewModel() {

    /** Что экран читает из базы; не прочиталось — говорит об этом и предлагает повторить. */
    val reading = ScreenReading()

    private val system = MutableStateFlow(readSystem())

    val state: StateFlow<OptionsPresentationDTO> =
        combine(operations.observeTroubles(), system) { troubles, (trouble, language) ->
            OptionsPresentationDTO(outstanding = troubles.size, permissionsTrouble = trouble, language = language)
        }.stateInScreen(viewModelScope, reading, OptionsPresentationDTO())

    /** Человек вернулся — из системных настроек или с экрана языка: спрашиваем систему заново. */
    fun refresh() {
        system.value = readSystem()
    }

    private fun readSystem(): Pair<Boolean, LanguageChoice> =
        permissionsNow(permissions, readiness).hasTrouble to languages.current().toChoice()
}

/** Что показывают строки «Опций». */
data class OptionsPresentationDTO(
    val outstanding: Int = 0,
    val permissionsTrouble: Boolean = false,
    val language: LanguageChoice = LanguageChoice.SYSTEM
)
