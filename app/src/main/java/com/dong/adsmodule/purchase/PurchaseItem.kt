package com.dong.adsmodule.purchase

data class PurchaseItem(
        val productId: String,
        val trialId: String? = null,
        val type: ProductType = ProductType.INAPP
) {
    init {
        require(productId.isNotBlank()) { "productId required" }
    }

    enum class ProductType { INAPP, SUBS }
}