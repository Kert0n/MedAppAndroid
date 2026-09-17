package com.kert0n.medapp.network.crpt

/**
 * Ответы «Честного знака» — по **живому** ответу пробы 2026-09-14 (PLAN H5): документации нет,
 * форма — `screen.items[]` с карточкой (фишки: страна, производитель), аптечным блоком и
 * атрибутами. Код, GTIN и серийный номер заменены заглушками, картинки и HTML-инструкция убраны.
 */
object CrptFixtures {

    /** Разделитель полей DataMatrix — GS: так его отдаёт сканер, и так он уходит в CRPT. */
    const val GS = "\u001d"

    /** Настоящий текст сканера начинается с `01`, поля `91`/`92` отделены GS. */
    const val SCANNED = "0104601234567890215ABCDE12345" + GS + "91EE11" + GS + "92dGVzdA=="

    /**
     * Ответ реестра **как он есть**: снят живым запросом 2026-09-17 и лежит файлом
     * `test/resources/crpt/found.json` целиком — с картинками, HTML-инструкцией и всеми блоками,
     * которых мы не читаем. Написанная от руки фикстура показывает то, что мы ожидали увидеть;
     * снятая — то, что реестр присылает на самом деле, и разойтись с ней нельзя незаметно.
     *
     * Заменены только опознавательные поля кода: `code`, `gtin`, `serial` и `id`. Чужой код в
     * репозитории — это возможность спросить реестр о чужой коробке.
     */
    val found: String = read("found.json")

    private fun read(name: String): String =
        checkNotNull(CrptFixtures::class.java.getResourceAsStream("/crpt/$name")) {
            "фикстура ответа реестра /crpt/$name не найдена"
        }.use { it.readBytes().decodeToString() }

    /**
     * Второй живой ответ той же пробы: составная дозировка, блок картинок и `receiptDate`
     * отсутствуют — форма та же, блоков меньше.
     */
    val lozenge = """
        {
          "codeFounded": true,
          "status": "item_sold_receipt_interm",
          "category": "drugs",
          "code": "01046012345678902100000000001",
          "productName": "Доритрицин",
          "expireDate": 1853884800000,
          "screen": {
            "items": [
              {"itemType": "main_card", "title": "Доритрицин", "chips": [{"chipType": "country", "value": "ГЕРМАНИЯ"}, {"chipType": "simple_text", "value": "МЕДИЦЕ ФАРМА ГМБХ & КО. КГ"}]},
              {"itemType": "pharmacy_search", "pharmacyData": {"title": "Доритрицин", "activeSubstance": "бензокаин+бензалкония хлорид+тиротрицин", "form": "таблетки для рассасывания", "dosage": "1.5 мг+1 мг+0.5 мг", "quantity": "10 шт"}},
              {"itemType": "attributes_card", "attrList": [{"label": "Описание", "value": "ТАБЛЕТКИ ДЛЯ РАССАСЫВАНИЯ, 1.5 мг+1 мг+0.5 мг"}, {"label": "Внутри упаковки", "value": "БЛИСТЕР - 1 шт по 10"}]},
              {"itemType": "attributes", "attrList": [{"label": "Производитель", "value": "МЕДИЦЕ ФАРМА ГМБХ & КО. КГ"}, {"label": "Импортёр", "value": "ООО «МЕДИЦЕ РУС»"}]}
            ]
          }
        }
    """.trimIndent()

    val notFound = """{"codeFounded": false, "code": "01046012345678902100000000000"}"""

    /** Не лекарство — форма ответа та же, категория иная. */
    val cosmetics = """
        {
          "codeFounded": true,
          "category": "cosmetics",
          "productName": "Крем для рук",
          "screen": {"items": [{"itemType": "pharmacy_search", "pharmacyData": {"form": "крем"}}]}
        }
    """.trimIndent()
}
