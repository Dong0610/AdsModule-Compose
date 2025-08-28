package com.dong.adsmodule.ads.config

/**
 * Adjust / analytics configuration.
 *
 * @property adjustToken Adjust app token.
 * @property eventNamePurchase Event name for purchase tracking.
 * @property eventAdImpressionValue Event name for ad impression value (revenue) tracking.
 * @property eventAdImpression Event name for ad impression count tracking.
 * @property fbAppId Facebook App ID used by related SDK integrations.
 */
class AdjustConfig (
    adjustToken: String
) {
    var adjustToken: String = adjustToken
    var eventNamePurchase: String = ""
    var eventAdImpressionValue: String = ""
    var eventAdImpression: String = ""
    var fbAppId: String = ""
}
