package com.kert0n.medapp.presentation

/**
 * Под какими именами навигация кладёт аргументы маршрута в `SavedStateHandle`.
 *
 * Представление маршрутов не видит — зависимости идут внутрь, и `presentation` об `app` не знает
 * (PLAN H1). Имена ему при этом нужны: по ним `ViewModel` достаёт своё место, пережившее смерть
 * процесса. Поэтому имя названо здесь один раз на всех, а то, что маршруты зовут свои поля так же,
 * держит `RouteTest`: разъехаться молча им нечем.
 */
object RouteArguments {

    const val MED_KIT_ID: String = "medKitId"

    const val PACKAGE_ID: String = "packageId"
}
