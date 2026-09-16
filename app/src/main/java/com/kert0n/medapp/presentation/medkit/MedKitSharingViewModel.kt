package com.kert0n.medapp.presentation.medkit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.Invitation
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitProjection
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.feature.medkits.MedKitInvitation
import com.kert0n.medapp.feature.medkits.MedKitPublishing
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import java.time.ZoneId
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Поделиться аптечкой (PLAN H3 №20): сделать полку общей и позвать в неё. Экран один, а лиц у него
 * два, и выбирает между ними не второй ключ маршрута, а сама полка: местная — решение с его
 * последствиями, общая — ключ приглашения.
 *
 * **Ключ живёт здесь, а не в маршруте** (PLAN G3, C1 «Ключ приглашения не бывает маршрутом»): он
 * секрет, а маршрут переживает смерть процесса в сохранённой стопке. Поворот ключ держит, смерть
 * процесса — нет, и тогда человек видит полку общей и «обновить код». Потери в этом нет: сервер
 * срока ключу не называет, ключ лежит у него в кэше и может уйти раньше (PLAN B6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = MedKitSharingViewModel.Factory::class)
class MedKitSharingViewModel @AssistedInject constructor(
    private val publishing: MedKitPublishing,
    private val invitations: MedKitInvitation,
    medKits: MedKitStorageRepository,
    today: Today,
    @Assisted private val medKitId: Uuid
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(medKitId: Uuid): MedKitSharingViewModel
    }

    /** То, чего нет в базе: выданный ключ и то, что человек сейчас делает на экране. */
    private val own = MutableStateFlow(Own())

    /** Полка вместе с зоной, в которой человек читает час: час приглашения — его местный. */
    private val shelf = today.observe().flatMapLatest { day -> medKits.observe(medKitId, day.date).map { day.zone to it } }

    val state: StateFlow<MedKitSharingUiState> = combine(shelf, own) { (zone, shelf), own ->
        shelf?.let { own.over(it, zone) } ?: MedKitSharingUiState.Gone
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedKitSharingUiState.Loading)

    /** Спросить перед необратимым: и публикация, и выдача ключа спрашивают (PLAN H3). */
    fun ask() {
        if (!own.value.isWorking) own.value = own.value.copy(isAsking = true, refusal = null)
    }

    fun dismissAsking() {
        if (!own.value.isWorking) own.value = own.value.copy(isAsking = false)
    }

    /**
     * Сделать полку общей. Второе нажатие, пока идёт первое, не делает ничего: человек ждёт от
     * него того же самого, а не второй публикации.
     */
    fun publish() {
        if (own.value.isWorking) return
        own.value = own.value.copy(isAsking = false, isWorking = true, refusal = null)
        viewModelScope.launch {
            own.value = when (publishing.publish(medKitId)) {
                // Полка помечена, и её новое состояние принесёт чтение: показывать его отсюда
                // значило бы завести второй источник правды о той же полке.
                MedKitPublishing.Outcome.PUBLISHING,
                MedKitPublishing.Outcome.ALREADY_SHARED,
                MedKitPublishing.Outcome.MED_KIT_GONE -> own.value.settled()
                MedKitPublishing.Outcome.BUSY -> own.value.refused(MedKitSharingRefusal.Busy)
            }
        }
    }

    /** Показать код во весь экран (№21) и убрать его оттуда. */
    fun showFullScreen() {
        if (own.value.invitation != null) own.value = own.value.copy(isFullScreen = true)
    }

    fun hideFullScreen() {
        own.value = own.value.copy(isFullScreen = false)
    }

    /** Позвать: выдать ключ. Прежний ключ заменяется новым — сервер выдаёт по ключу на запрос. */
    fun invite() {
        if (own.value.isWorking) return
        own.value = own.value.copy(isAsking = false, isWorking = true, refusal = null)
        viewModelScope.launch {
            own.value = when (val outcome = invitations.invite(medKitId)) {
                is MedKitInvitation.Outcome.Invited -> own.value.settled().copy(invitation = outcome.invitation)
                MedKitInvitation.Outcome.MedKitGone -> own.value.settled()
                MedKitInvitation.Outcome.NotShared -> own.value.refused(MedKitSharingRefusal.NotShared)
                MedKitInvitation.Outcome.Busy -> own.value.refused(MedKitSharingRefusal.Busy)
                is MedKitInvitation.Outcome.Unavailable -> own.value.refused(MedKitSharingRefusal.Unavailable(outcome.reason))
            }
        }
    }

    private data class Own(
        val invitation: Invitation? = null,
        val isAsking: Boolean = false,
        val isWorking: Boolean = false,
        val isFullScreen: Boolean = false,
        val refusal: MedKitSharingRefusal? = null
    ) {

        // Новый ключ показывается там же, где человек смотрел прежний: он нажал «обновить
        // код», не закрывая полный экран, и закрывать его за него незачем.
        fun settled(): Own = copy(isWorking = false, refusal = null)

        fun refused(refusal: MedKitSharingRefusal): Own = copy(isWorking = false, refusal = refusal)

        fun over(shelf: MedKitProjection, zone: ZoneId): MedKitSharingUiState = when {
            shelf.publication == MedKit.Publication.LOCAL && shelf.status != MedKitStatus.PUBLISHING ->
                MedKitSharingUiState.Deciding(shelf.name, isAsking, isWorking, refusal)
            // Полка уехала, а половины полки не бывает: пока не доехало содержимое, звать некуда
            // (PLAN D2, E5). Ключа в этом состоянии нет и быть не может.
            !shelf.acceptsInvitations -> MedKitSharingUiState.OnItsWay(shelf.name)
            else -> MedKitSharingUiState.Shared(
                shelf.name, invitation?.toPresentationDTO(zone), isAsking, isWorking, refusal,
                // Полного экрана без ключа не бывает: ключ мог уйти вместе с полкой.
                isFullScreen = isFullScreen && invitation != null
            )
        }
    }
}

/**
 * Что показывает экран. Случаи различает поведение человека: ждать нечего [Loading]; полки нет
 * [Gone]; местную решают сделать общей [Deciding]; уехавшую ждут [OnItsWay]; в общую зовут [Shared].
 */
sealed interface MedKitSharingUiState {

    data object Loading : MedKitSharingUiState

    /** Полки нет: делиться нечем. */
    data object Gone : MedKitSharingUiState

    /** Местная: видны последствия и решение. */
    data class Deciding(
        val name: String,
        val isAsking: Boolean = false,
        val isWorking: Boolean = false,
        val refusal: MedKitSharingRefusal? = null
    ) : MedKitSharingUiState

    /**
     * О полке принято решение, и оно едет серверу: последствия показывать поздно, а звать рано —
     * половины полки не бывает (PLAN D2, E5). Так же выглядит и полка, которую убирают.
     */
    data class OnItsWay(val name: String) : MedKitSharingUiState

    /** Общая: [invitation] — выданный ключ, `null` — ещё не звали. */
    data class Shared(
        val name: String,
        val invitation: InvitationPresentationDTO? = null,
        val isAsking: Boolean = false,
        val isWorking: Boolean = false,
        val refusal: MedKitSharingRefusal? = null,
        /** Код показан во весь экран (№21) — состояние этого же экрана, а не отдельный маршрут. */
        val isFullScreen: Boolean = false
    ) : MedKitSharingUiState
}

/** Почему не вышло. Человек в каждом случае делает разное: ждёт, публикует, повторяет. */
sealed interface MedKitSharingRefusal {

    /** О полке уже принято другое решение — ждём его ответа. */
    data object Busy : MedKitSharingRefusal

    /** Звать в местную некуда: на сервере её нет. */
    data object NotShared : MedKitSharingRefusal

    /** Сервера нет: причина и повтор. */
    data class Unavailable(val reason: Unavailability) : MedKitSharingRefusal
}
