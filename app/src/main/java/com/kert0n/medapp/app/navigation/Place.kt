package com.kert0n.medapp.app.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.kert0n.medapp.R

/**
 * Пять мест нижней навигации (PLAN H3). Набор закрыт и известен целиком, поэтому перечисление:
 * шестое место — это изменение продукта, а не данных.
 *
 * Значок и подпись — ссылки на ресурсы, а не готовые `ImageVector`: перечисление остаётся
 * обычным значением, которое можно построить где угодно, а не только внутри композиции. Красит
 * значок сам `Icon` по `LocalContentColor`, поэтому второго набора цветов для тёмной темы нет.
 *
 * Значка два: у выбранного места он залит — так Material отличает, где человек стоит, не одним
 * лишь цветом. Залитого варианта может и не быть: у `qr_code_scanner` он совпадает с обычным —
 * узор заливать нечем (issue #34), поэтому [iconSelected] пуст, а не повторяет файл-двойник.
 *
 * Подписи короткие: на большом экране с поднятым масштабом пять мест делят ширину по 80 dp, и
 * «Аналитика» с «Настройками» обрезались многоточием (`BigScaled`). Порядок объявления —
 * порядок на экране.
 */
enum class Place(
    val key: Screen,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val iconSelected: Int? = null
) {
    MED_KITS(
        Screen.MedKits, R.string.tab_med_kits,
        R.drawable.ic_tab_med_kits, R.drawable.ic_tab_med_kits_filled
    ),
    PLAN(
        Screen.Plan, R.string.tab_plan,
        R.drawable.ic_tab_plan, R.drawable.ic_tab_plan_filled
    ),
    SCANNER(Screen.Scanner, R.string.tab_scanner, R.drawable.ic_tab_scanner),
    REPORTS(
        Screen.Reports, R.string.tab_reports,
        R.drawable.ic_tab_analytics, R.drawable.ic_tab_analytics_filled
    ),
    OPTIONS(
        Screen.Options, R.string.tab_options,
        R.drawable.ic_tab_settings, R.drawable.ic_tab_settings_filled
    );

    /** Где человек стоит, видно и формой значка, а не только цветом (PLAN H3). */
    @DrawableRes
    fun icon(selected: Boolean): Int = if (selected) iconSelected ?: icon else icon

    companion object {

        /** Место, с которого приложение начинается и в которое возвращает последний возврат. */
        val first: Place get() = entries.first()
    }
}
