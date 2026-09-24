package com.kert0n.medapp.network.pack

import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.network.server.toNetworkDTO
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.pack.PackageSyncState

/**
 * Сравнивает сохранённую доменную форму с тем, что известно о пачке, и оставляет только
 * изменившееся.
 *
 * Отправлять поле, которого человек не трогал, нельзя: PATCH перетёр бы им чужую правку тем же
 * самым значением, и потеря выглядела бы как «ничего не менялось».
 *
 * Состояние синхронизации приходит аргументом, а не читается у пачки: «существует ли она на
 * сервере» — вопрос к предусловию, и домен на него не отвечает.
 *
 * Количество и единица здесь не заполняются вовсе — это отдельные сценарии пересчёта и смены
 * единицы (PLAN D3, E1), у них свои операции и свои предупреждения.
 */
fun PackageFacts.toPatchNetworkMapping(
    current: Package,
    sync: PackageSyncState
): PackagePatchNetworkMapping {
    val known = current.facts
    // Правка была только локальной — на провод не идёт ничего. Вопрос задаётся структуре, а не
    // перечислением шести полей, где седьмое забудут (PLAN E2, D3).
    if (shared == known.shared) return PackagePatchNetworkMapping(dto = null, formIdClearUnsupported = false)
    val dto = shared.toPatchNetworkDTO(known.shared)
    val formCleared = known.form != null && form == null
    return PackagePatchNetworkMapping(
        dto = dto.takeIf { !it.isEmpty },
        formIdClearUnsupported = formCleared && sync.isOnServer
    )
}

/**
 * Разница двух общих описаний как намерение PATCH: неизменённое не отправляется, очищенный текст
 * становится `""`. [version] — предусловие; при подготовке запроса очереди оно замораживается
 * вместе с телом.
 */
fun PackageSharedFacts.toPatchNetworkDTO(
    known: PackageSharedFacts,
    version: ResourceVersion? = null
): PackagePatchNetworkDTO = PackagePatchNetworkDTO(
    name = name.takeIf { it != known.name },
    formId = form?.takeIf { it != known.form }?.id,
    category = clearableText(known.category, category),
    manufacturer = clearableText(known.manufacturer, manufacturer),
    country = clearableText(known.country, country),
    description = clearableText(known.description, description),
    version = version?.toNetworkDTO()
)

/**
 * Перевод доменного «сведений нет» в сетевое «очистить».
 *
 * В домене отсутствие — `null`; на проводе `null` значит «не трогать», а очистка — `""`.
 * Один этот `when` и есть весь перевод между двумя смыслами, и он живёт в мапперe, а не в модели.
 */
private fun clearableText(current: String?, saved: String?): String? = when {
    saved == current -> null   // не изменилось — не трогаем
    saved == null -> ""        // очищено — на проводе это пустая строка
    else -> saved
}
