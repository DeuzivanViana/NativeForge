package io.nativeforge.app.device

import android.content.Context
import android.os.Build
import android.os.PowerManager
import io.nativeforge.core.common.ThermalLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's thermal status from Android (10 and newer; older ones always report
 * [ThermalLevel.NONE]). Builds and streamed programs read it to back off when it is hot.
 */
class ThermalMonitor(context: Context) {
    private val _level = MutableStateFlow(ThermalLevel.NONE)
    val level: StateFlow<ThermalLevel> = _level.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val power = context.getSystemService(PowerManager::class.java)
                _level.value = ThermalLevel.fromAndroidStatus(power.currentThermalStatus)
                power.addThermalStatusListener(context.mainExecutor) { status -> _level.value = ThermalLevel.fromAndroidStatus(status) }
            }
        }
    }
}
