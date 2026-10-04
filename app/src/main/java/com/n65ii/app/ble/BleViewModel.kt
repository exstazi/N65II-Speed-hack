package com.n65ii.app.ble

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.n65ii.app.model.DiscoveredDevice

class BleViewModel(application: Application) : AndroidViewModel(application) {
    val manager = BleManager(application.applicationContext)
    val state = manager.state
    fun connect(device: DiscoveredDevice) = manager.connect(device)
    override fun onCleared() {
        manager.close()
        super.onCleared()
    }
}
