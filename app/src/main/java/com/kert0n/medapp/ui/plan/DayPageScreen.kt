package com.kert0n.medapp.ui.plan

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.plan.DayItemPresentationDTO
import com.kert0n.medapp.presentation.plan.DayPagePresentationDTO
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.TIME
import com.kert0n.medapp.ui.words
import java.time.LocalTime
import kotlin.uuid.Uuid

/**
 * На сколько дней вперёд листается план. Столько же вперёд календарь пишет пункты
 * (`CourseCalendar.WINDOW` — шестьдесят дней): дальше страница показывала бы одни ожидаемые дозы,
 * а человек в такую даль не заглядывает. Число здесь, а не в чтении: это предел листания, свойство
 * страницы.
 */
private const val DAYS_AHEAD = 60

/**
 * Страницы дня (PLAN H3 №12).
 *
 * Страница держит **сдвиг**, а не дату: в полночь та же страница начинает показывать новый день,
 * и переставлять её не приходится. Листается только вперёд — назад ответы уже даны, и менять их
 * пока нечем (issue #44), поэтому нулевая страница и есть первая.
 *
 * Чтение каждой страницы спрашивается отдельно — [page] зовётся в вёрстке той страницы, которой
 * оно принадлежит: листающий человек видит две страницы разом, и общее чтение показало бы соседней
 * чужие строки.
 */
@Composable
fun DayPages(
    page: @Composable (daysAhead: Int) -> ScreenState<DayPagePresentationDTO>,
    onOpen: (DayItemPresentationDTO) -> Unit,
    onConfirm: (Uuid) -> Unit,
    modifier: Modifier = Modifier
) {
    val pager = rememberPagerState(pageCount = { DAYS_AHEAD + 1 })
    HorizontalPager(state = pager, modifier = modifier.fillMaxSize()) { daysAhead ->
        DayPage(page(daysAhead), onOpen, onConfirm)
    }
}

/** Один день: заголовок с датой, пункты и то, что пришлось на этот день помимо них. */
@Composable
private fun DayPage(
    state: ScreenState<DayPagePresentationDTO>,
    onOpen: (DayItemPresentationDTO) -> Unit,
    onConfirm: (Uuid) -> Unit
) {
    val day = (state as? ScreenState.Ready)?.value
    // Первое чтение базы ещё не пришло: говорить «ничего не назначено» рано — это была бы неправда.
    if (day == null) {
        LoadingState(Modifier.fillMaxSize())
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            text = day.header(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (day.isEmpty) {
            EmptyState(text = stringResource(R.string.plan_day_empty), modifier = Modifier.fillMaxSize())
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(day.items, key = { it.key }) { item -> DayRow(item, onOpen, onConfirm) }
            shelf(R.string.plan_day_also, day.alsoOnThisDay, onOpen)
        }
    }
}

/**
 * Заголовок дня: число и — для ближних дней — слово. «Сегодня» человек читает быстрее, чем
 * сравнивает даты, а дальше третьего дня слова нет: «через пять дней» не короче самой даты.
 */
@Composable
private fun DayPagePresentationDTO.header(): String {
    val word = when (daysAhead) {
        0 -> stringResource(R.string.plan_day_today)
        1 -> stringResource(R.string.plan_day_tomorrow)
        else -> null
    }
    val number = DAY.format(date)
    return if (word == null) number else "$number · $word"
}

/** Полка «ещё в этот день»: пустая не показывается — заголовок без строк ничего не говорит. */
private fun LazyListScope.shelf(
    @StringRes title: Int,
    items: List<DayItemPresentationDTO>,
    onOpen: (DayItemPresentationDTO) -> Unit
) {
    if (items.isEmpty()) return
    item(key = "shelf-$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
    items(items, key = { it.key }) { item -> DayRow(item, onOpen, onConfirm = {}) }
}

/**
 * Строка дня: время, лечение, доза и пачка, а справа — ответ. У неотвеченного пункта это кнопка:
 * в день приёмов несколько, и просить два нажатия на каждый — просить лишнего (H3 №12). У
 * отвеченного — слово о том, что записано; значок повторяет его глазу и потому экранному чтецу не
 * адресуется: он прочёл бы состояние дважды.
 *
 * Нажатие на саму строку ведёт на карточку пункта — туда, где приём меняют, а не отвечают наспех.
 */
@Composable
private fun DayRow(
    item: DayItemPresentationDTO,
    onOpen: (DayItemPresentationDTO) -> Unit,
    onConfirm: (Uuid) -> Unit
) {
    ListItem(
        overlineContent = { Text(TIME.format(item.at)) },
        headlineContent = { Text(item.title) },
        supportingContent = { Text(item.details()) },
        leadingContent = { Icon(painterResource(item.state.icon), contentDescription = null) },
        trailingContent = {
            val intakeId = item.intakeId
            if (item.canAnswer && intakeId != null) {
                FilledTonalButton(onClick = { onConfirm(intakeId) }, enabled = !item.isAnswering) {
                    Text(stringResource(R.string.plan_answer_now))
                }
            } else {
                Text(item.stateWords(), style = MaterialTheme.typography.labelLarge)
            }
        },
        modifier = Modifier.clickable(enabled = item.intakeId != null) { onOpen(item) }
    )
}

/** Доза и, если она известна, пачка: «2 таблетка · Нурофен». */
private fun DayItemPresentationDTO.details(): String =
    listOfNotNull(dose.words(), packageName).joinToString(" · ")

/**
 * Состояние словами. У принятого рядом стоит время: «принят в 09:12» — то, что человек и хотел
 * узнать, открывая день.
 */
@Composable
private fun DayItemPresentationDTO.stateWords(): String = when (state) {
    DayItemPresentationDTO.State.PLANNED -> stringResource(R.string.intake_state_planned)
    DayItemPresentationDTO.State.TAKEN -> stringResource(R.string.intake_state_taken, answeredAt.words())
    DayItemPresentationDTO.State.MISSED -> stringResource(R.string.intake_state_missed)
    DayItemPresentationDTO.State.CANCELLED -> stringResource(R.string.intake_state_cancelled)
    DayItemPresentationDTO.State.EXPECTED -> stringResource(R.string.intake_state_expected)
    DayItemPresentationDTO.State.ONE_OFF -> stringResource(R.string.intake_state_one_off, answeredAt.words())
}

/** Время ответа — у принятого оно есть всегда; пустая строка тут значила бы потерянный факт. */
private fun LocalTime?.words(): String = this?.let { TIME.format(it) }.orEmpty()

@get:DrawableRes
private val DayItemPresentationDTO.State.icon: Int
    get() = when (this) {
        DayItemPresentationDTO.State.PLANNED -> R.drawable.ic_schedule
        DayItemPresentationDTO.State.TAKEN -> R.drawable.ic_check_circle
        DayItemPresentationDTO.State.MISSED -> R.drawable.ic_warning
        DayItemPresentationDTO.State.CANCELLED -> R.drawable.ic_close
        DayItemPresentationDTO.State.EXPECTED -> R.drawable.ic_calendar_month
        DayItemPresentationDTO.State.ONE_OFF -> R.drawable.ic_medication
    }

/**
 * Чем строка отличается от соседних в списке. У пункта за окном календаря записи ещё нет, и
 * своего номера тоже: его называют лечение и время, которых на день приходится не больше одного.
 */
private val DayItemPresentationDTO.key: String
    get() = intakeId?.toString() ?: "$courseId-$at"
