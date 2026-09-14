package com.kert0n.medapp.domain.scan

/**
 * Что дал распознаватель: формат и текст (PLAN H5). Формат называет **он**, а не длина строки —
 * DataMatrix и QR приглашения различаются символикой, а не числом знаков.
 */
data class ScannedCode(val format: CodeFormat, val text: String)

/**
 * Три формата, потому что три пути: DataMatrix спрашивают у «Честного знака», QR — это
 * приглашение в полку (экран 22), всё остальное — «код не поддерживается» (C1 «Сканирование
 * кодов»): EAN-13 описывает товар, а не эту коробку, и не отправляется.
 */
enum class CodeFormat { DATA_MATRIX, QR, OTHER }
