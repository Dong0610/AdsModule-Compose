package com.dong.adsmodule.purchase

import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.Purchase

/**
 * Immutable snapshot of a Play Billing purchase.
 */
data class PurchaseResult(
        val orderId: String,
        val packageName: String,
        val products: List<String>,
        val purchaseTimeMs: Long,
        val purchaseState: Int,          // Purchase.PurchaseState.*
        val purchaseToken: String,
        val signature: String?,
        val originalJson: String?,
        val quantity: Int,
        val acknowledged: Boolean
) {
    init {
        require(purchaseToken.isNotBlank()) { "purchaseToken required" }
    }

    companion object {
        @JvmStatic
        fun from(p: Purchase, packageName: String) = PurchaseResult(
                orderId = p.orderId ?: "",
                packageName = packageName,
                products = p.products ?: emptyList(),
                purchaseTimeMs = p.purchaseTime,
                purchaseState = p.purchaseState,
                purchaseToken = p.purchaseToken,
                signature = p.signature,
                originalJson = p.originalJson,
                quantity = p.quantity,
                acknowledged = p.isAcknowledged
        )
    }

    fun toAcknowledgeParams(): AcknowledgePurchaseParams =
            AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
}