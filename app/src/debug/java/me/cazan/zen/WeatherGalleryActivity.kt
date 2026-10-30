package me.cazan.zen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.cazan.zen.ui.Txt
import me.cazan.zen.ui.WeatherIcon
import me.cazan.zen.ui.Zen

class WeatherGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val animate = intent.getBooleanExtra("animate", true)
        val codes = listOf(0 to "Clear", 2 to "Partly", 3 to "Cloudy", 45 to "Fog", 51 to "Drizzle",
            61 to "Rain", 80 to "Showers", 71 to "Snow", 85 to "Snow showers", 95 to "Thunder")
        setContent {
            Column(Modifier.fillMaxSize().background(Zen.Background).padding(40.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                listOf(true to "Day", false to "Night").forEach { (day, name) ->
                    Txt(name, size = 18.sp, color = Zen.TextDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        codes.forEach { (code, label) ->
                            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                WeatherIcon(code, day, animate, size = 56.dp)
                                Txt(label, size = 12.sp, color = Zen.TextDim)
                            }
                        }
                    }
                }
            }
        }
    }
}
