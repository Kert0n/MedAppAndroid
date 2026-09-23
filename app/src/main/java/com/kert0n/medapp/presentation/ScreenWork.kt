package com.kert0n.medapp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.attempt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Работа экрана, которая не удалась по причине вне приложения, — полный диск, испорченная база, —
 * не закрывает приложение (ТЗ 4.3). У такого сбоя два владельца, по тому, что человек делает дальше:
 * нажатие, которое не сработало, он повторяет сам, и о нём говорит оболочка ([ScreenFailures]);
 * экран, которому нечего показать, предлагает прочитать заново ([ScreenReading]). Отмена —
 * человек ушёл с экрана — сбоем не бывает. Голых `launch` и `stateIn` в `presentation/` нет —
 * это держит `ScreenFailureOwnershipTest`.
 */

/** Нажатия, которые не сработали, — одно на приложение место, откуда о них говорит оболочка. */
@Singleton
class ScreenFailures @Inject constructor() {

    private val happened = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val failures: SharedFlow<Unit> = happened.asSharedFlow()

    fun report() {
        happened.tryEmit(Unit)
    }
}

/** Оболочке — нажатия, которые не сработали: она говорит о них над всеми экранами. */
@HiltViewModel
class ScreenFailuresViewModel @Inject constructor(failures: ScreenFailures) : ViewModel() {
    val failures: SharedFlow<Unit> = failures.failures
}

/**
 * Действие экрана по нажатию. Сбой снимает с экрана «идёт работа» — [undo] возвращает то, что было
 * до нажатия, введённое остаётся на месте, — и говорится оболочкой; нажать можно снова.
 */
fun ViewModel.act(failures: ScreenFailures, undo: () -> Unit = {}, work: suspend CoroutineScope.() -> Unit): Job =
    viewModelScope.launch {
        attempt { work() }.onFailure {
            undo()
            failures.report()
        }
    }

/**
 * Что экран читает из базы. Не прочиталось — экран говорит об этом вместо содержимого и предлагает
 * повторить; повтор открывает чтение заново. Случай один — «данные устройства не читаются», — и
 * другого у местного чтения не бывает.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenReading {

    private val attempts = MutableStateFlow(0)
    private val _failed = MutableStateFlow<Unavailability?>(null)
    private val pending = mutableListOf<suspend CoroutineScope.() -> Unit>()
    private var scope: CoroutineScope? = null

    val failed: StateFlow<Unavailability?> = _failed.asStateFlow()

    /** Прочитать заново всё, что не прочиталось: потоки открываются снова, разовые чтения зовутся снова. */
    fun retry() {
        if (_failed.value == null) return
        _failed.value = null
        attempts.update { it + 1 }
        val again = pending.toList()
        pending.clear()
        again.forEach { load(requireNotNull(scope), it) }
    }

    /** Поток, чей сбой — «не прочиталось», а не падение; повтор открывает его заново. */
    fun <T> guarded(flow: Flow<T>): Flow<T> = attempts.flatMapLatest {
        flow.catch { failure ->
            if (failure is CancellationException) throw failure
            _failed.value = Unavailability.DEVICE_STORAGE
        }
    }

    /** Разовое чтение при открытии экрана; не прочиталось — повтор зовёт его снова, а удавшееся — нет. */
    fun load(scope: CoroutineScope, read: suspend CoroutineScope.() -> Unit): Job {
        this.scope = scope
        return scope.launch {
            attempt { read() }.onFailure {
                pending += read
                _failed.value = Unavailability.DEVICE_STORAGE
            }
        }
    }

    /** Поток, который экран слушает сам, всю свою жизнь. */
    fun <T> listen(scope: CoroutineScope, flow: Flow<T>, each: suspend (T) -> Unit): Job =
        scope.launch { guarded(flow).collect { each(it) } }
}

/** Состояние экрана из потока, у сбоя которого есть владелец — [reading]. */
fun <T> Flow<T>.stateInScreen(scope: CoroutineScope, reading: ScreenReading, initial: T): StateFlow<T> =
    reading.guarded(this).stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)
