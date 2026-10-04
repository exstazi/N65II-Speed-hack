package com.n65ii.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.n65ii.app.ble.BleViewModel
import com.n65ii.app.ui.ScooterBleApp
import com.n65ii.app.ui.theme.N65IITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            N65IITheme {
                val vm: BleViewModel = viewModel()
                ScooterBleApp(vm)
            }
        }
    }
}
