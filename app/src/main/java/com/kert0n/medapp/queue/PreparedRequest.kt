package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.value.Quantity
import java.time.Instant
import java.util.Objects

/**
 * Подготовленный изменяющий запрос: путь, тело и версии предусловий, замороженные **до** первой
 * отправки. На повторе он не пересобирается — иначе неустановленный расход ушёл бы со свежим
 * предусловием и списался бы дважды (PLAN E2, E3).
 *
 * Доменным расчётам он недоступен: это транспорт, а не смысл.
 */
class PreparedRequest(
    val method: String,
    val path: String,
    query: Map<String, String> = emptyMap(),
    val body: String? = null,
    val drugVersion: ResourceVersion? = null,
    val claimsVersion: ResourceVersion? = null,
    val quantityBefore: Quantity? = null,
    val mineBefore: Quantity? = null,
    val preparedAt: Instant
) {
    /**
     * Свой снимок, а не переданная карта: `val` защищает ссылку, а не содержимое, и параметры,
     * оставшиеся у вызывающего, ушли бы на повторе изменёнными — то самое, чего заморозка
     * запроса и не допускает.
     */
    val query: Map<String, String> = query.toMap()

    init {
        require(method.isNotBlank()) { "у запроса есть метод" }
        require(path.isNotBlank()) { "у запроса есть путь" }
        require(
            quantityBefore == null || mineBefore == null ||
                quantityBefore.unit == mineBefore.unit
        ) { "остаток и бронь до запроса измеряются одной единицей" }
    }

    /** Единица предусловий: она одна на обе величины и остаётся той, что была при подготовке. */
    val unit get() = quantityBefore?.unit ?: mineBefore?.unit

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PreparedRequest &&
                method == other.method &&
                path == other.path &&
                query == other.query &&
                body == other.body &&
                drugVersion == other.drugVersion &&
                claimsVersion == other.claimsVersion &&
                quantityBefore == other.quantityBefore &&
                mineBefore == other.mineBefore &&
                preparedAt == other.preparedAt
            )

    override fun hashCode(): Int = Objects.hash(
        method, path, query, body, drugVersion, claimsVersion, quantityBefore, mineBefore, preparedAt
    )

    override fun toString(): String = "PreparedRequest($method $path, подготовлен $preparedAt)"
}
