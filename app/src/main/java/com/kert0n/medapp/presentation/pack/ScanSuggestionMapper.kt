package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.scan.PackageSuggestion
import com.kert0n.medapp.presentation.value.toPresentationDTO

/**
 * Предложение кода — в поля формы новой коробки (PLAN H3 «Набор сканера»). Сканер ничего не
 * показывает от себя: он **предзаполняет обычный экран**, и дальше человек идёт привычным путём
 * (решение владельца 2026-09-17).
 *
 * **Заполняется только пустое**: ответ приходит из сети, и к этому времени человек уже мог начать
 * печатать — затереть набранное им значило бы спорить с тем, кто держит коробку в руках
 * (U1 «ввод не затирается значением, дочитанным из базы»).
 *
 * Количество и дозировка сюда не едут вовсе: «20 капсул в 2 блистерах» и «1.5 мг+1 мг» числом не
 * становятся, и выдумывать за реестр нечем (PLAN H5). Форма выпуска подставляется, только когда её
 * узнал словарь: подставленная наугад, она не дала бы подключить коробку к лечению.
 */
fun PackageSuggestion.filling(form: PackageFormPresentationDTO): PackageFormPresentationDTO =
    form.copy(
        name = form.name.ifBlank { name.orEmpty() },
        form = form.form ?: this.form?.toPresentationDTO(),
        manufacturer = form.manufacturer.ifBlank { manufacturer.orEmpty() },
        country = form.country.ifBlank { country.orEmpty() },
        expiresOn = form.expiresOn.ifBlank { expiresOn?.toPresentationDTO()?.text.orEmpty() }
    )
