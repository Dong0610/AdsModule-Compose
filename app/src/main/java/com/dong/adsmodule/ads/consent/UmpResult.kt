import android.app.Activity
import com.dong.adsmodule.ads.consent.GoogleMobileAdsConsentManager
import com.google.android.ump.FormError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UmpResult(
    val canRequestAds: Boolean,          // consentInfo.canRequestAds()
    val privacyOptionsRequired: Boolean, // có cần nút Privacy Options
    val consentStringGranted: Boolean,   // IABTCF_PurposeConsents bit 1
    val error: FormError?                // null nếu không lỗi
)

/**
 * Gọi ở Splash: yêu cầu UMP + show form nếu cần, rồi trả kết quả.
 * - Gọi từ coroutine (lifecycleScope/LaunchedEffect).
 */
suspend fun requestUmp(
    activity: Activity,
    reset: Boolean = false,
    isTestDebug: Boolean = false,
    testDeviceHashedId: String? = null,
    tagForUnderAge: Boolean = false
): UmpResult = withContext(Dispatchers.Main) {
    val mgr = GoogleMobileAdsConsentManager.getInstance(activity).apply {
        this.isTestDebug = isTestDebug
        this.deviceHashedId = testDeviceHashedId
        this.tagForUnderAge = tagForUnderAge
    }

    val outcome = mgr.requestAndShowIfRequired(activity, reset) // suspend
    UmpResult(
        canRequestAds = outcome.canRequestAds,
        privacyOptionsRequired = outcome.privacyOptionsRequired,
        consentStringGranted = outcome.consentStringGranted,
        error = outcome.formError
    )
}
