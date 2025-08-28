package com.dong.adsmodule.ui.screen

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.dong.adsmodule.ads.ad.AppOpenManager
import com.dong.adsmodule.ads.ad.NativeAdCore
import com.dong.adsmodule.ads.ad.NativePreloadState
import com.dong.adsmodule.ui.AppViewTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    companion object {
        val adUnitIds by mutableStateOf(
            listOf(
                "ca-app-pub-3940256099942544/2247696110",
                "ca-app-pub-3940256099942544/1044960115",

            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

       NativeAdCore.preloadAd("native_main", this@MainActivity, adUnitIds = adUnitIds,2 , )

        AppOpenManager.getInstance()
            .setResumeAdUnits(listOf("ca-app-pub-3940256099942544/9257395921"))
        setContent {

            LaunchedEffect(Unit) {
                delay(1000)
                startActivity(Intent(this@MainActivity, AdTestActivity::class.java))
            }



            AppViewTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier
                        .fillMaxSize(1f)
                        .padding(innerPadding)) {
                        Greeting(
                            name = "Android",
                            modifier = Modifier
                        )
                        val state by NativeAdCore.getPreloadState("native_main")
                            .collectAsState(initial = NativePreloadState.Idle)

                        when (val s = state) {
                            NativePreloadState.Idle -> {/* nothing */}
                            is NativePreloadState.Progress ->
                                Text("Preloading ${s.loaded}/${s.requested}…")
                            is NativePreloadState.ItemSuccess -> {
                                Text("Loaded item ${s.loaded}/${s.requested} from ${s.adUnitId}")

                            }
                            is NativePreloadState.Finished -> {
                                Text("Done: ${s.loaded}/${s.requested} (available=${s.available})")


                            }

                            NativePreloadState.Cancelled ->
                                Text("Preload cancelled")
                        }
                    }

                }
            }
        }
    }
}
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AppViewTheme {
        Greeting("Android")
    }
}