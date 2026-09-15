package com.kert0n.medapp.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer

/**
 * Стопка на каждое место (PLAN H3).
 *
 * Место — не экран, а комната: человек заходит в неё, уходит вглубь (полка → коробка → правка) и
 * возвращается; перейдя в другую комнату и вернувшись, он застаёт свою такой, какой оставил.
 * Одна общая стопка этого не умеет: уход в чужое место либо стирал бы глубину своего, либо
 * складывал бы чужие экраны в одну кучу, и возврат вёл бы не туда.
 *
 * Стопки и выбранное место — величины, и переживают смерть процесса сами: ключи сериализуются,
 * а хранит их сохранённое состояние композиции.
 */
class TabStacks internal constructor(
    chosen: MutableState<NavKey>,
    private val stacks: Map<NavKey, NavBackStack<NavKey>>
) {

    /** Место, в котором человек сейчас. */
    var place: NavKey by chosen
        private set

    /** Стопка нынешнего места: первый лист — само место, последний — то, что человек видит. */
    private val here: NavBackStack<NavKey> get() = stacks.getValue(place)

    /** Что человек видит. */
    val screen: NavKey? get() = here.lastOrNull()

    /**
     * Последний ход был сменой места, а не уходом вглубь или возвратом внутри него. По этому
     * признаку оболочка выбирает движение: места сменяются мгновенно, глубина едет. Спрашивать
     * ключи бесполезно — с глубины чужого места на корень своего ведёт та же смена места.
     */
    var switchedPlace: Boolean by mutableStateOf(false)
        private set

    /** Человек в глубине места: жест назад снимет лист, а не сменит место. */
    val isDeep: Boolean get() = here.size > 1

    /**
     * Стопки, из которых собирается показ: своя и, если человек не у первого места, стопка
     * первого — под ней. Так возврат с корня чужого места проваливается к первому месту, а не
     * закрывает приложение молча: под верхней стопкой всегда есть что показать.
     */
    private val shown: List<NavKey>
        get() = if (place == Place.first.key) listOf(place) else listOf(Place.first.key, place)

    /**
     * Перейти: один из пяти ключей — это смена комнаты, любой другой — лист поверх нынешней
     * стопки. Что перед нами, спрашивается у самих стопок: место — то, у чего стопка есть.
     */
    fun go(target: NavKey) {
        switchedPlace = target in stacks
        if (switchedPlace) place = target else here.add(target)
    }

    /**
     * Вернуться — один поступок человека, палец это был или кнопка. С глубины снимается верхний
     * лист; с корня чужого места — переход к первому месту; с корня первого возврат сюда не
     * доходит вовсе, и приложение закрывает система.
     */
    fun back() {
        switchedPlace = !isDeep
        if (isDeep) here.removeAt(here.lastIndex) else place = Place.first.key
    }

    /** Вернуть место к его началу: повторное нажатие на своё место в нижней панели. */
    fun backToRoot(target: NavKey) {
        val stack = stacks[target] ?: return
        switchedPlace = false
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Показ: листы своей стопки поверх листов первой. */
    @Composable
    internal fun entries(provider: (NavKey) -> NavEntry<NavKey>): List<NavEntry<NavKey>> {
        val decorated = stacks.mapValues { (_, stack) ->
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    // Состояние экрана живёт столько же, сколько его лист в стопке: снятый лист
                    // уносит с собой свою ViewModel, а место, оставленное ради соседнего, — нет.
                    rememberViewModelStoreNavEntryDecorator()
                ),
                entryProvider = provider
            )
        }
        return shown.flatMap { decorated[it].orEmpty() }
    }
}

/** Пять стопок и выбранное место — всё, что помнит оболочка о том, где человек. */
@Composable
fun rememberTabStacks(): TabStacks {
    val chosen = rememberSerializable(serializer = MutableStateSerializer(NavKeySerializer())) {
        mutableStateOf<NavKey>(Place.first.key)
    }
    val stacks: Map<NavKey, NavBackStack<NavKey>> =
        Place.entries.associate { place -> place.key to rememberNavBackStack(place.key) }
    return remember(stacks) { TabStacks(chosen, stacks) }
}
