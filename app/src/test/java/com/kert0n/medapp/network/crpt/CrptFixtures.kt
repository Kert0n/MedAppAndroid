package com.kert0n.medapp.network.crpt

/**
 * Ответы «Честного знака» — по **живому** ответу пробы 2026-09-14 (PLAN H5): документации нет,
 * форма — `screen.items[]` с карточкой (фишки: страна, производитель), аптечным блоком и
 * атрибутами. Код, GTIN и серийный номер заменены заглушками, картинки и HTML-инструкция убраны.
 */
object CrptFixtures {

    /** Разделитель полей DataMatrix — GS: так его отдаёт сканер, и так он уходит в CRPT. */
    const val GS = ""

    /** Настоящий текст сканера начинается с `01`, поля `91`/`92` отделены GS. */
    const val SCANNED = "0104601234567890215ABCDE12345" + GS + "91EE11" + GS + "92dGVzdA=="

    val found = """
        {
          "id": 855204142,
          "checkDate": 1789401743131,
          "codeFounded": true,
          "status": "item_sold_receipt",
          "statusV2": "item_sold_receipt",
          "verified": true,
          "country": 5,
          "known": true,
          "category": "drugs",
          "categoryV2": "drugs",
          "context": "scan",
          "code": "0104601234567890210000000000091EE1192AAAA",
          "gtin": "04601234567890",
          "serial": "0000000000000",
          "productName": "Цетрин",
          "outerStatus": "in_sale",
          "receiptDate": 1763200500000,
          "applicationDate": 1745776145000,
          "codeType": "datamatrix",
          "expireDate": 1838073600000,
          "lastOperationDate": 1763200500000,
          "isBlocked": false,
          "wrongDocs": false,
          "screen": {
            "navBar": [{"order": 10, "itemType": "alarm_add", "alarmName": "Цетрин"}],
            "items": [
              {
                "order": 10, "itemType": "main_card", "title": "Цетрин",
                "statusCard": {"statusType": "neutral", "title": "Товар продан 15 ноября 2025", "complaintButton": false},
                "chips": [
                  {"order": 10, "chipType": "country", "value": "ИНДИЯ"},
                  {"order": 20, "chipType": "simple_text", "value": "Д-Р РЕДДИ`С ЛАБОРАТОРИС ЛТД."}
                ]
              },
              {
                "order": 20, "itemType": "expiration_card",
                "expirations": [{"showDetails": false, "expirationDescription": [{"order": 10, "icon": "ok", "expiration": "Годен ещё больше года", "conditions": "До 31 марта 2028"}]}]
              },
              {"order": 40, "itemType": "group_card", "images": [], "averagePriceRequest": false},
              {
                "order": 50, "itemType": "pharmacy_search",
                "pharmacyData": {"title": "Цетрин", "activeSubstance": "цетиризин", "form": "таблетки покрытые пленочной оболочкой", "dosage": "10 мг", "quantity": "30 шт", "image": "https://example.invalid/cetrine.jpg"}
              },
              {
                "order": 60, "itemType": "instruction",
                "attrList": [{"label": "Показания к применению", "value": "<P>Взрослым и детям с 6 лет</P>"}],
                "batch": "B0000000"
              },
              {
                "order": 70, "itemType": "attributes_card",
                "attrList": [
                  {"label": "Международное наименование", "value": "ЦЕТИРИЗИН"},
                  {"label": "Описание", "value": "ТАБЛЕТКИ ПОКРЫТЫЕ ПЛЕНОЧНОЙ ОБОЛОЧКОЙ, 10 мг"},
                  {"label": "Внутри упаковки", "value": "БЛИСТЕР - 3 шт по 10"},
                  {"label": "Комплектность", "value": "~"}
                ]
              },
              {
                "order": 90, "itemType": "attributes",
                "attrList": [
                  {"label": "Производитель", "value": "Д-Р РЕДДИ`С ЛАБОРАТОРИС ЛТД."},
                  {"label": "Импортёр", "value": "ОБЩЕСТВО С ОГРАНИЧЕННОЙ ОТВЕТСТВЕННОСТЬЮ \"ДР. РЕДДИ`С ЛАБОРАТОРИС\""},
                  {"label": "Серия", "value": "B0000000"}
                ]
              },
              {"order": 100, "itemType": "doc_links", "docLinksData": [{}]},
              {"order": 110, "itemType": "complaint_from_card"}
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
