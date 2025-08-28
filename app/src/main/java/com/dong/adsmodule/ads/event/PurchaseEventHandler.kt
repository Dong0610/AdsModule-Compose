package com.dong.adsmodule.ads.event;

interface PurchaseListener {
    fun onProductPurchased(orderId: String, originalJson: String)
    fun displayErrorMessage(message: String)
    fun onUserCancelBilling()
}
// SAM-friendly in Kotlin
fun interface BillingListener {
    fun onInitBillingFinished(responseCode: Int)
}

// SAM-friendly in Kotlin
fun interface UpdatePurchaseListener {
    fun onUpdateFinished()
}

abstract class SimplePurchaseListener : PurchaseListener {
    override fun onProductPurchased(orderId: String, originalJson: String) {}
    override fun displayErrorMessage(message: String) {}
    override fun onUserCancelBilling() {}
}