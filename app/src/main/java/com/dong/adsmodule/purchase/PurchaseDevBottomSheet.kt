package com.dong.adsmodule.purchase

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.android.billingclient.api.ProductDetails
import com.dong.adsmodule.R
import com.dong.adsmodule.ads.event.PurchaseListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject

class PurchaseDevBottomSheet(
    private val typeIap: Int,                      // 1 = inapp, 2 = subs (AppPurchase.TYPE_IAP)
    private val productDetails: ProductDetails?,   // may be null in DEV
    context: Context,
    private val purchaseListener: PurchaseListener?
) : BottomSheetDialog(context) {

    private var txtTitle: TextView? = null
    private var txtDescription: TextView? = null
    private var txtId: TextView? = null
    private var txtPrice: TextView? = null
    private var txtContinuePurchase: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_billing_test)

        txtTitle = findViewById(R.id.txtTitle)
        txtDescription = findViewById(R.id.txtDescription)
        txtId = findViewById(R.id.txtId)
        txtPrice = findViewById(R.id.txtPrice)
        txtContinuePurchase = findViewById(R.id.txtContinuePurchase)

        // Bind UI
        val pd = productDetails
        val productId = pd?.productId ?: AppPurchase.PRODUCT_ID_TEST

        txtTitle?.text = pd?.title.orEmpty()
        txtDescription?.text = pd?.description.orEmpty()
        txtId?.text = productId
        txtPrice?.text = when (typeIap) {
            AppPurchase.TYPE_IAP.PURCHASE ->
                pd?.oneTimePurchaseOfferDetails?.formattedPrice.orEmpty()
            AppPurchase.TYPE_IAP.SUBSCRIPTION ->
                pd?.subscriptionOfferDetails
                    ?.firstOrNull()
                    ?.pricingPhases
                    ?.pricingPhaseList
                    ?.firstOrNull()
                    ?.formattedPrice.orEmpty()
            else -> ""
        }

        // Confirm (simulated purchase in DEV)
        txtContinuePurchase?.setOnClickListener {
            // Mark purchased in DEV and send a fake Purchase JSON to match your listener API.
            AppPurchase.getInstance().setPurchase(true)
            purchaseListener?.onProductPurchased(
                /* orderId = */ "GPA.TEST.DEV-${System.currentTimeMillis()}",
                /* originalJson = */ buildFakePurchaseJson(productId, typeIap)
            )
            dismiss()
        }
        setCanceledOnTouchOutside(true)
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun buildFakePurchaseJson(productId: String, typeIap: Int): String {
        // A minimal, Play-like payload your code can parse in DEV
        // (fields commonly present in Purchase.getOriginalJson()).
        return JSONObject().apply {
            put("productId", productId)
            put("productIds", listOf(productId))
            put("purchaseState", 1) // PURCHASED
            put("acknowledged", true)
            put("purchaseToken", "TEST_TOKEN_${System.currentTimeMillis()}")
            put("orderId", "GPA.TEST.DEV-${System.currentTimeMillis()}")
            put("packageName", context.packageName)
            put("quantity", 1)
            put("obfuscatedAccountId", "dev")
            put("obfuscatedProfileId", "dev")
            put("isAutoRenewing", typeIap == AppPurchase.TYPE_IAP.SUBSCRIPTION)
        }.toString()
    }
}
