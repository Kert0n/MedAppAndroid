package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.RouteArguments
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.presentation.value.toDomain
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Пересчёт и утилизация (PLAN H3 №9). Человек приходит сюда с одним наблюдением — «в коробке не
 * столько, сколько записано», — а объясняет его по-разному, и от объяснения зависит, что значит
 * названное им число.
 *
 * Отсчитывается разница от того числа, **которое человек видел** на экране: на полке,
 * отвечающей серверу, уезжает именно она (PLAN C1). Поэтому `seen` берётся из той же оценки, что
 * показана, а не дочитывается заново в момент нажатия.
 *
 * Уходящее в ноль спрашивается: коробки после этого не будет, и это решение человека, а не
 * следствие арифметики.
 */
@HiltViewModel
class PackageAmountViewModel @Inject constructor(
    private val adjusting: PackageAdjusting,
    private val vocabulary: VocabularyStorageRepository,
    packages: PackageStorageRepository,
    savedState: SavedStateHandle
) : ViewModel() {

    private val packageId: Uuid =
        Uuid.parse(checkNotNull(savedState[RouteArguments.PACKAGE_ID]) { "маршрут пересчёта называет коробку" })

    private val form = MutableStateFlow(PackageAmountPresentationDTO())

    private val progress = MutableStateFlow(Progress())

    /**
     * Коробка, как её прочитали. Держится доменной проекцией, а не строками экрана: от числа,
     * которое человек видел, отсчитывается разница, и собирать его обратно из строки значило бы
     * разбирать то, что уже было величиной.
     *
     * Прочитана или ещё нет — часть ответа: `null` внутри [Reading.Read] значит «коробки нет», а
     * само отсутствие чтения не значит ничего.
     */
    private val seen = MutableStateFlow<Reading>(Reading.Unread)

    init {
        viewModelScope.launch { packages.observe(packageId).collect { seen.value = Reading.Read(it) } }
    }

    val state: StateFlow<PackageAmountUiState> =
        combine(seen, form, progress) { reading, form, progress ->
            val pack = (reading as? Reading.Read)?.pack
            PackageAmountUiState(
                pack = pack?.toPresentationDTO(),
                form = form,
                isGone = reading is Reading.Read && pack == null,
                error = progress.error,
                asksToEmpty = progress.asksToEmpty,
                isDone = progress.done
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackageAmountUiState())

    fun edit(edited: PackageAmountPresentationDTO) {
        form.value = edited
        if (progress.value.error != null) progress.value = Progress()
    }

    /**
     * Записать. Ноль сначала спрашивается: коробки после него не будет. Второе нажатие, пока
     * идёт первое, ничего не начинает.
     *
     * Замок ставится **до** `launch`, а не внутри записи: между нажатием и ею стоит чтение
     * словаря, и пока оно шло, второе нажатие проходило мимо замка — «выбросил 3» списывало
     * шесть.
     */
    fun submit() {
        val now = progress.value
        if (now.working || now.done) return
        progress.value = Progress(working = true)
        viewModelScope.launch {
            when (val parsed = parse()) {
                is ParsedInput.Rejected -> reject(parsed.error)
                is ParsedInput.Parsed ->
                    if (parsed.value.emptiesTheBox) progress.value = Progress(asksToEmpty = true)
                    else apply(parsed.value)
            }
        }
    }

    /** Человек согласился, что коробки не станет. Второе согласие — то же самое согласие. */
    fun confirmEmptying() {
        val now = progress.value
        if (!now.asksToEmpty || now.working) return
        progress.value = Progress(asksToEmpty = true, working = true)
        viewModelScope.launch {
            val parsed = parse()
            if (parsed is ParsedInput.Parsed) apply(parsed.value) else progress.value = Progress()
        }
    }

    /** Пока согласие исполняется, отказаться уже не от чего: запись идёт. */
    fun dismissEmptying() {
        if (progress.value.working) return
        progress.value = Progress()
    }

    private suspend fun apply(change: Change) {
        progress.value = Progress(working = true)
        progress.value = when (adjusting.adjust(packageId, change.action)) {
            PackageAdjusting.Outcome.ADJUSTED, PackageAdjusting.Outcome.ENDED -> Progress(done = true)
            PackageAdjusting.Outcome.GONE -> Progress(error = PackageAmountError.Gone)
            PackageAdjusting.Outcome.UNUSABLE -> Progress(error = PackageAmountError.Busy)
        }
    }

    /**
     * Что человек назвал. `seen` — число, которое он видел: от него отсчитывается разница на
     * полке, отвечающей серверу (PLAN C1).
     */
    private suspend fun parse(): ParsedInput<Change, PackageAmountError> {
        val pack = (seen.value as? Reading.Read)?.pack ?: return ParsedInput.Rejected(PackageAmountError.Gone)
        val shown = pack.availability.effective
        val typed = QuantityPresentationDTO(form.value.amount, shown.unit.toPresentationDTO())
        val amount = when (val parsed = typed.toDomain(vocabulary.snapshot())) {
            is ParsedInput.Rejected -> return ParsedInput.Rejected(PackageAmountError.Amount(parsed.error))
            is ParsedInput.Parsed -> parsed.value
        }
        return when (form.value.change) {
            AmountChange.RECOUNT -> ParsedInput.Parsed(
                Change(PackageAdjusting.Action.Recount(shown, amount), emptiesTheBox = amount.isZero)
            )
            AmountChange.DISPOSAL -> when {
                // Выбросить ноль — это ничего не сделать.
                amount.isZero -> ParsedInput.Rejected(PackageAmountError.NothingToDispose)
                // Выбросить больше, чем лежало, — ошибка человека, а не повод списать до нуля:
                // домен в минус не уходит и молча превратил бы «выбросил 30» в «выбросил 20».
                amount.exceeds(shown) -> ParsedInput.Rejected(PackageAmountError.MoreThanThereIs)
                else -> ParsedInput.Parsed(
                    Change(
                        action = PackageAdjusting.Action.Dispose(shown, amount),
                        emptiesTheBox = shown.minusOrZero(amount).isZero
                    )
                )
            }
        }
    }

    /**
     * Больше ли одно количество другого. У [Quantity] сравнения нет — домену оно не нужно, он
     * вычитает без ухода в минус, — а форме нужно: она различает «выбросил всё» и «выбросил
     * больше, чем было», и это разные ответы человеку.
     */
    private fun Quantity.exceeds(other: Quantity): Boolean = !minusOrZero(other).isZero

    private fun reject(error: PackageAmountError) {
        progress.value = Progress(error = error)
    }

    private class Change(val action: PackageAdjusting.Action, val emptiesTheBox: Boolean)

    /**
     * Чтение коробки: его ещё не было или оно было и дало вот это. Без этого различия пустое
     * начальное значение читалось как «коробки нет», и экран успевал сказать это вслух, пока
     * база ещё отвечала.
     */
    private sealed interface Reading {
        data object Unread : Reading
        data class Read(val pack: PackageProjection?) : Reading
    }

    private data class Progress(
        val working: Boolean = false,
        val asksToEmpty: Boolean = false,
        val error: PackageAmountError? = null,
        val done: Boolean = false
    )
}

/** Что показывает экран количества. */
data class PackageAmountUiState(
    val pack: PackagePresentationDTO? = null,
    val form: PackageAmountPresentationDTO = PackageAmountPresentationDTO(),
    val isGone: Boolean = false,
    val error: PackageAmountError? = null,
    /** Названное число опустошает коробку, и человека об этом спрашивают. */
    val asksToEmpty: Boolean = false,
    /** Записано: экран уходит. */
    val isDone: Boolean = false
) {
    val isLoading: Boolean get() = pack == null && !isGone
}
