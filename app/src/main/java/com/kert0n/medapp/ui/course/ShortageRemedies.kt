package com.kert0n.medapp.ui.course

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.course.ShortagePresentationDTO
import com.kert0n.medapp.ui.NavigationRow

/**
 * Что делать с нехваткой (PLAN H3 «Набор общей полки»). Экран, который только называет беду,
 * человеку не помогает: он видит «не хватает 19 приёмов с 11.09» и не знает, чем это лечить.
 * Отсюда три выхода, и каждый ведёт туда, где он делается.
 *
 * Лист, а не место: человек уже стоит у своего лечения и возвращается туда же. Расписание и доза
 * ни одним из трёх не трогаются — их назначил врач (PLAN C1 «Нехватка»).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortageRemedies(
    shortage: ShortagePresentationDTO,
    onAttach: () -> Unit,
    onReallocate: () -> Unit,
    onBuy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Shortage(
                shortage,
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
            Text(
                stringResource(R.string.course_shortage_what_to_do),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            NavigationRow(
                icon = R.drawable.ic_medication,
                text = stringResource(R.string.course_shortage_attach),
                supporting = stringResource(R.string.course_shortage_attach_explained),
                onClick = onAttach
            )
            NavigationRow(
                icon = R.drawable.ic_tune,
                text = stringResource(R.string.course_shortage_reallocate),
                supporting = stringResource(R.string.course_shortage_reallocate_explained),
                onClick = onReallocate
            )
            NavigationRow(
                icon = R.drawable.ic_shopping_cart,
                text = stringResource(R.string.course_shortage_buy),
                supporting = stringResource(R.string.course_shortage_buy_explained),
                onClick = onBuy
            )
        }
    }
}
