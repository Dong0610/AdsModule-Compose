package com.dong.adsmodule.ui.screen

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dong.adsmodule.ads.ad.NativeAdCore
import com.dong.adsmodule.ads.ad.NativeAdState
import com.dong.adsmodule.ads.event.AdCallback
import com.dong.adsmodule.ui.AppViewTheme
import com.dong.adsmodule.ui.BodyText
import com.dong.adsmodule.ui.ButtonCta
import com.dong.adsmodule.ui.CornerRadii
import com.dong.adsmodule.ui.HeadlineText
import com.dong.adsmodule.ui.IconView
import com.dong.adsmodule.ui.MediaView
import com.dong.adsmodule.ui.NativeAdCard
import com.dong.adsmodule.ui.adBackground
import com.dong.adsmodule.ui.adCornerRadius
import com.dong.adsmodule.ui.adCorners
import com.dong.adsmodule.ui.adFillMaxWidth
import com.dong.adsmodule.ui.adGradientLinear
import com.dong.adsmodule.ui.adHeight
import com.dong.adsmodule.ui.adPadding
import com.dong.adsmodule.ui.adSize
import com.dong.adsmodule.ui.adTextStyle
import com.dong.adsmodule.ui.adWidth
import com.dong.adsmodule.ui.screen.MainActivity.Companion.adUnitIds

class AdTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppViewTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        Modifier
                            .fillMaxSize(1f)
                            .padding(top = innerPadding.calculateTopPadding())
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Greeting(
                                name = "Main Ad Activity ",
                                modifier = Modifier.padding(innerPadding)
                            )
                            NativeAdView()
                        }
                    }
                }
            }
        }
    }
    @Composable
    fun NativeAdView() {
        val task = remember(adUnitIds) {
            NativeAdCore.requestAdPreload(
                tag = "native_main",
                adUnitIds = adUnitIds,
                appContext = applicationContext,
                adCallback = object : AdCallback() {
                    override fun onAdImpression() {
                        super.onAdImpression()
                        Log.d("TagAdAction", "onAdImpression: Ad impression")
                    }

                    override fun onAdClicked() {
                        super.onAdClicked()
                        Log.d("TagAdAction", "onAdImpression: Ad Click")
                    }
                }
            )
        }
        val task2 = remember(adUnitIds) {
            NativeAdCore.requestAdPreload(
                tag = "native_main",
                adUnitIds = adUnitIds,
                appContext = applicationContext,
                adCallback = object : AdCallback() {
                    override fun onAdImpression() {
                        super.onAdImpression()
                        Log.d("TagAdAction", "onAdImpression: Ad impression")
                    }

                    override fun onAdClicked() {
                        super.onAdClicked()
                        Log.d("TagAdAction", "onAdImpression: Ad Click")
                    }
                }
            )
//            NativeAdCore.request(appContext = this@AdTestActivity, adUnitIds, adCallback = object : AdCallback(){
//                override fun onAdImpression() {
//                    super.onAdImpression()
//                    Log.d("TagAdAction", "onAdImpression: Ad impression")
//                }
//
//                override fun onAdClicked() {
//                    super.onAdClicked()
//                    Log.d("TagAdAction", "onAdImpression: Ad Click")
//                }
//            })
        }
        val adState by task.state.collectAsState(initial = NativeAdState.Idle)
        val adState2 by task2.state.collectAsState(initial = NativeAdState.Idle)

        LaunchedEffect(adState) {
            Log.d("NativeAdStateLoad", "Ad state $adState")
        }

        DisposableEffect(Unit) {
            onDispose { task.cancel()
            task2.cancel()}
        }

        NativeAdCard(adState, nativeView = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(Color.White)
                    .border(1.dp, Color.LightGray, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth(1f)
                ) {
                    IconView(modifier = Modifier.size(48.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        HeadlineText(
                            Modifier
                                .adFillMaxWidth()
                                .adTextStyle(
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(
                                            includeFontPadding = false
                                        ),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.W600
                                    ), color = Color.Yellow
                                ),
                        )
                        BodyText(
                            Modifier
                                .fillMaxWidth()
                                .adTextStyle(
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(
                                            includeFontPadding = false
                                        ),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.W600
                                    ), color = Color.Black
                                )
                        )
                    }
                }

                MediaView(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(120.dp)
                )
                ButtonCta(
                    modifier = Modifier.padding(top = 12.dp),
                    ctaStyle =
                        Modifier
                            .adPadding(top = 12.dp)
                            .adFillMaxWidth()
                            .adBackground(color = Color.Black)
                            .adHeight(48.dp)
                            .adTextStyle(style = TextStyle(
                                color = Color.White

                            ), color = Color.White)
                            .adCornerRadius(40.dp),
                )
            }
        }, loading = {
            Text("Ad loading")
        }, timeout = {
            Text("Time out $it")
        }, error = {
            Text("Error $it")
        }, exhausted = {
            Text("Error $it")
        })
        Spacer(Modifier.height(12.dp))
        NativeAdCard(adState2, nativeView = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(Color.White)
                    .border(1.dp, Color.LightGray, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth(1f)
                ) {
                    IconView(modifier = Modifier.size(48.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        HeadlineText(
                            Modifier
                                .adFillMaxWidth()
                                .adTextStyle(
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(
                                            includeFontPadding = false
                                        ),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.W600
                                    ), color = Color.Black
                                ),
                        )
                        BodyText(
                            Modifier
                                .fillMaxWidth()
                                .adTextStyle(
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(
                                            includeFontPadding = false
                                        ),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.W600
                                    ), color = Color.Black
                                )
                        )
                    }
                }
                ButtonCta(
                    modifier = Modifier.padding(top = 12.dp),
                    ctaStyle =
                        Modifier
                            .adPadding(top = 12.dp)
                            .adFillMaxWidth()
                            .adGradientLinear(
                                listOf(Color.Green, Color.Blue,Color.Magenta), angleDeg = 90f
                            )
                            .adHeight(48.dp)
                            .adCorners(CornerRadii(12.dp, 12.dp, 12.dp, 12.dp)),
                )
            }
        }, loading = {
            Text("Ad loading")
        }, timeout = {
            Text("Time out $it")
        }, error = {
            Text("Error $it")
        }, exhausted = {
            Text("Error $it")
        })

        Button(onClick = {
            task.requestAd()
            task2.requestAd()
        }) {
            Text("Request new Ad")
        }
    }
}