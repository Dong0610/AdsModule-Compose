package com.dong.adsmodule.ads.consent

import android.app.Activity
import android.content.Context
import androidx.annotation.MainThread
import com.google.android.ump.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Quản lý UMP consent: 1 API duy nhất để yêu cầu consent + show form nếu cần.
 * Dùng được trong lib ads (compose hay view đều OK).
 */
class GoogleMobileAdsConsentManager private constructor(
    appContext: Context
) {
    private val appContext = appContext.applicationContext
    private val consentInfo: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(this.appContext)
    // Chặn gọi trùng trong 1 vòng đời (tùy bạn giữ hay bỏ)
    private val inProgress = AtomicBoolean(false)
    /** Cấu hình debug (tuỳ chọn) */
    var isTestDebug: Boolean = false
    var deviceHashedId: String? = null
    var tagForUnderAge: Boolean = false
    /** Trạng thái tiện ích */
    val canRequestAds: Boolean get() = consentInfo.canRequestAds()
    val isPrivacyOptionsRequired: Boolean
        get() = consentInfo.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    // -------- Public API
    /**
     * Phiên bản suspend: gọi từ Coroutine (khuyến nghị).
     * - Tự request consent info
     * - Tự load & show form nếu cần
     * - Trả về ConsentOutcome gói đủ thông tin.
     */
    @MainThread
    suspend fun requestAndShowIfRequired(
        activity: Activity,
        reset: Boolean = false
    ): ConsentOutcome = suspendCancellableCoroutine { cont ->
        if (!inProgress.compareAndSet(false, true)) {
            // Nếu đang xử lý, trả về snapshot hiện tại
            cont.resume(currentOutcome(activity)) {}
            return@suspendCancellableCoroutine
        }
        val params = buildRequestParams(activity)
        if (reset) consentInfo.reset()

        consentInfo.requestConsentInfoUpdate(
            activity,
            params,
            {
                // Sau khi cập nhật info, nếu form required => load & show
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                    activity
                ) { formError ->
                    // formError != null: có lỗi khi load/show form
                    val outcome = currentOutcome(activity).copy(formError = formError)
                    inProgress.set(false)
                    cont.resume(outcome) {}
                }
            },
            { reqErr ->
                // Lỗi update info: vẫn trả outcome hiện tại + kèm lỗi
                val outcome = currentOutcome(activity).copy(formError = reqErr)
                inProgress.set(false)
                cont.resume(outcome) {}
            }
        )
    }
    /**
     * Phiên bản callback: nếu bạn không muốn dùng coroutine.
     */
    @MainThread
    fun requestAndShowIfRequired(
        activity: Activity,
        reset: Boolean = false,
        callback: (ConsentOutcome) -> Unit
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            val r = requestAndShowIfRequired(activity, reset)
            callback(r)
        }
    }
    /**
     * Mở Privacy Options (nếu REQUIRED).
     */
    @MainThread
    fun showPrivacyOptionsForm(
        activity: Activity,
        onDismiss: (FormError?) -> Unit = {}
    ) {
        if (!isPrivacyOptionsRequired) {
            onDismiss(null); return
        }
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formErr ->
            onDismiss(formErr)
        }
    }
    // -------- Private helpers
    private fun buildRequestParams(activity: Activity): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(tagForUnderAge)

        if (isTestDebug && !deviceHashedId.isNullOrBlank()) {
            val dbg = ConsentDebugSettings.Builder(activity)
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                .addTestDeviceHashedId(deviceHashedId!!)
                .build()
            builder.setConsentDebugSettings(dbg)
        }
        return builder.build()
    }
    /** Đọc nhanh outcome hiện tại (sau mỗi bước). */
    private fun currentOutcome(ctx: Context): ConsentOutcome {
        val consentStringGranted = readIabTcfPurpose1(ctx) // Mục đích 1 (lưu trữ/truy cập thiết bị)
        return ConsentOutcome(
            canRequestAds = consentInfo.canRequestAds(),
            privacyOptionsRequired = isPrivacyOptionsRequired,
            consentStringGranted = consentStringGranted,
            formError = null
        )
    }
    /**
     * Đọc IAB TCF v2 PurposeConsents: "IABTCF_PurposeConsents"
     * - Trả true nếu rỗng (không có - coi như không chặn) hoặc bit 1 = '1'
     */
    private fun readIabTcfPurpose1(context: Context): Boolean {
        val sp = context.getSharedPreferences(
            context.packageName + "_consent_pref",
            Context.MODE_PRIVATE
        )
        val s = sp.getString("IABTCF_PurposeConsents", "") ?: ""
        return s.isEmpty() || s[0] == '1' // bit 1
    }

    companion object {
        @Volatile
        private var instance: GoogleMobileAdsConsentManager? = null

        fun getInstance(context: Context): GoogleMobileAdsConsentManager =
            instance ?: synchronized(this) {
                instance ?: GoogleMobileAdsConsentManager(context.applicationContext).also {
                    instance = it
                }
            }
    }
}
/** Kết quả gói gọn, đủ dùng để quyết định khởi tạo/quảng cáo */
data class ConsentOutcome(
    val canRequestAds: Boolean,
    val privacyOptionsRequired: Boolean,
    val consentStringGranted: Boolean,
    val formError: FormError? // null nếu không lỗi
)
