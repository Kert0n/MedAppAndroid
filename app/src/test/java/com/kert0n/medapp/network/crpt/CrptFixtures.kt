package com.kert0n.medapp.network.crpt

/**
 * Ответы «Честного знака», как их наблюдал референс (PLAN H5): документации нет, форма — по
 * `screen.items[]` с аптечным блоком и атрибутами. Серийный номер — заглушка.
 */
object CrptFixtures {

    /** Разделитель полей DataMatrix — GS: так его отдаёт сканер, и так он уходит в CRPT. */
    const val GS = ""

    /** Настоящий текст сканера начинается с `01`, поля `91`/`92` отделены GS. */
    const val SCANNED = "0104601234567890215ABCDE12345" + GS + "91EE11" + GS + "92dGVzdA=="

    val found = """
        {
          "codeFounded": true,
          "checkResult": true,
          "category": "drugs",
          "code": "01046012345678902100000000000",
          "productName": "Ибупрофен таблетки покрытые пленочной оболочкой 200 мг №20",
          "expireDate": 1804714200000,
          "screen": {
            "items": [
              {"itemType": "group_card", "images": ["https://example.invalid/1.png"]},
              {"itemType": "pharmacy_search", "pharmacyData": {
                 "title": "Ибупрофен", "activeSubstance": "Ибупрофен",
                 "form": "таблетки, покрытые пленочной оболочкой", "dosage": "200 мг", "quantity": "20 шт"}},
              {"itemType": "attrs", "attrList": [
                 {"label": "Форма выпуска", "value": "Таблетки"},
                 {"label": "Количество единиц потребления", "value": "20"},
                 {"label": "Производитель", "value": "ОАО «Синтез»"},
                 {"label": "Страна производства", "value": "Россия"}
              ]}
            ]
          },
          "somethingNew": {"nested": [1, 2, 3]}
        }
    """.trimIndent()

    val notFound = """{"codeFounded": false, "code": "01046012345678902100000000000"}"""

    val cosmetics = """
        {
          "codeFounded": true,
          "category": "cosmetics",
          "productName": "Крем для рук",
          "screen": {"items": [{"itemType": "pharmacy_search", "pharmacyData": {"form": "крем"}}]}
        }
    """.trimIndent()
}
