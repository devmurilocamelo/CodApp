package dev.oldcam.camera.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dev.oldcam.camera.stream.DeviceReading

/**
 * Escuta o broadcast ACTION_BATTERY_CHANGED, que o sistema envia sempre que nível,
 * temperatura ou estado de carga mudam (≈ a cada 1% ou 0,1 °C).
 *
 * É um broadcast "sticky": ao registrar, recebemos imediatamente o último valor,
 * então a tela já abre com dados.
 *
 * `onReading: (DeviceReading) -> Unit` é um parâmetro do tipo FUNÇÃO (lambda):
 * quem cria o monitor diz o que fazer com cada leitura — parecido com um callback em JS.
 */
class DeviceMonitor(
    private val context: Context,
    private val onReading: (DeviceReading) -> Unit,
) {
    // `object : BroadcastReceiver() { ... }` = classe anônima (como `new class extends` em PHP).
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onReading(parse(intent))
        }
    }
    private var registered = false

    fun start() {
        if (registered) return
        // RECEIVER_NOT_EXPORTED: só o sistema/nosso app entregam; outros apps não injetam dados falsos.
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        sticky?.let { receiver.onReceive(context, it) }
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    companion object {
        /** Extrai nível (%), temperatura (°C) e se está carregando do Intent da bateria. */
        fun parse(intent: Intent): DeviceReading {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            // O sistema informa a temperatura em DÉCIMOS de grau: 312 = 31,2 °C.
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
            val tempC = if (tenths != Int.MIN_VALUE) tenths / 10f else null
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            return DeviceReading(percent, tempC, charging)
        }
    }
}
