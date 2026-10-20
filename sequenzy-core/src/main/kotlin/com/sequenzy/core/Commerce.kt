package com.sequenzy.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A product, with the same fields as the web SDK's `viewedProduct`. */
data class CommerceProduct(
    val providerProductId: String,
    val providerVariantId: String? = null,
    val title: String? = null,
    val variantTitle: String? = null,
    val imageUrl: String? = null,
    val url: String? = null,
    /** Major units, for example 19.99; or pass [priceCents]. */
    val price: Double? = null,
    val priceCents: Long? = null,
    val currency: String? = null,
    val sku: String? = null,
) {
    internal fun toMap(): Map<String, Any?> = buildMap {
        put("providerProductId", providerProductId)
        providerVariantId?.let { put("providerVariantId", it) }
        title?.let { put("title", it) }
        variantTitle?.let { put("variantTitle", it) }
        imageUrl?.let { put("imageUrl", it) }
        url?.let { put("url", it) }
        price?.let { put("price", it) }
        priceCents?.let { put("priceCents", it) }
        currency?.let { put("currency", it) }
        sku?.let { put("sku", it) }
    }
}

data class CommerceCartLine(val product: CommerceProduct, val quantity: Int, val lineItemId: String? = null) {
    internal fun toMap(): Map<String, Any?> =
        product.toMap() + ("quantity" to quantity) + (lineItemId?.let { mapOf("lineItemId" to it) } ?: emptyMap())
}

data class CommerceCart(
    val lineItems: List<CommerceCartLine>,
    val cartId: String? = null,
    val totalQuantity: Int? = null,
    val totalPriceCents: Long? = null,
    val currency: String? = null,
) {
    internal fun toMap(): Map<String, Any?> = buildMap {
        cartId?.let { put("cartId", it) }
        put("itemCount", lineItems.size)
        totalQuantity?.let { put("totalQuantity", it) }
        totalPriceCents?.let { put("totalPriceCents", it) }
        currency?.let { put("currency", it) }
        put("lineItems", lineItems.map { it.toMap() })
    }
}

data class CommerceCollection(
    val providerCollectionId: String,
    val title: String? = null,
    val handle: String? = null,
    val url: String? = null,
) {
    internal fun toMap(): Map<String, Any?> = buildMap {
        put("providerCollectionId", providerCollectionId)
        title?.let { put("title", it) }
        handle?.let { put("handle", it) }
        url?.let { put("url", it) }
    }
}

/**
 * A change to the signed-in contact's profile. Omitted values stay as they
 * are; set [clearFirstName] or [clearLastName], or an attribute to null, to
 * clear one.
 */
data class ProfileUpdate(
    val firstName: String? = null,
    val lastName: String? = null,
    val attributes: Map<String, Any?>? = null,
    val clearFirstName: Boolean = false,
    val clearLastName: Boolean = false,
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        if (clearFirstName) put("firstName", JsonNull) else firstName?.let { put("firstName", it) }
        if (clearLastName) put("lastName", JsonNull) else lastName?.let { put("lastName", it) }
        attributes?.let { put("attributes", toJsonObject(it)) }
    }
}

/** A newsletter or waitlist signup through a published Sequenzy form. */
data class SubscribeRequest(
    val formId: String,
    val email: String,
    val firstName: String? = null,
    val lastName: String? = null,
    /** Values for the form's custom fields. */
    val attributes: Map<String, Any?>? = null,
    /** For forms with a phone field; E.164 such as +14155550123. */
    val phone: String? = null,
)
