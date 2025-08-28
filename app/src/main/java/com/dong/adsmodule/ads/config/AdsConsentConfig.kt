package com.dong.adsmodule.ads.config

/**
 * Consent configuration for ads.
 *
 * @property enableUMP Whether UMP (User Messaging Platform) is enabled.
 * @property enableDebug Whether debug mode is enabled for testing.
 * @property testDevice Test device ID for consent debugging.
 * @property resetData Whether to reset consent data.
 */
class AdsConsentConfig(
    var enableUMP: Boolean
) {
    var enableDebug: Boolean = false
    var testDevice: String = ""
    var resetData: Boolean = false
}
