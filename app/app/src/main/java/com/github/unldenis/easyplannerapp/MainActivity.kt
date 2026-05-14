package com.github.unldenis.easyplannerapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.github.unldenis.easyplanner.Point
import com.github.unldenis.easyplanner.distance
import com.github.unldenis.easyplannerapp.ui.theme.EasyPlannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EasyPlannerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        ffiSample = distance(Point(0.0, 0.0), Point(3.0, 4.0)),
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, ffiSample: Double, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name! (Rust FFI distance 0→3,4 = $ffiSample)",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    EasyPlannerTheme {
        Greeting("Android", ffiSample = 0.0)
    }
}