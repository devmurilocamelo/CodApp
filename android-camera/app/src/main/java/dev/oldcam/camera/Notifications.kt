package dev.oldcam.camera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.oldcam.camera.stream.StreamService
import dev.oldcam.camera.stream.StreamUiState
import dev.oldcam.camera.ui.MainActivity

/**
 * Centraliza canais e notificações.
 *
 * Desde o Android 8 toda notificação pertence a um "canal". O usuário pode
 * silenciar cada canal separadamente nas configurações do sistema.
 */
object Notifications {
    const val CHANNEL_STREAM = "stream"   // notificação fixa do serviço (silenciosa)
    const val CHANNEL_ALERTS = "alerts"   // temperatura, falha ao iniciar (com som)

    const val ID_STREAM = 1
    const val ID_TEMPERATURE = 2
    const val ID_START_BLOCKED = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_STREAM, context.getString(R.string.channel_stream), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    /** PendingIntent = "intenção guardada" que o sistema executa depois, em nosso nome (ex.: ao tocar). */
    private fun openAppIntent(context: Context, autoStart: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTOSTART, autoStart)
        // FLAG_IMMUTABLE: outro app não consegue alterar o Intent guardado (exigido no Android 12+).
        return PendingIntent.getActivity(
            context, if (autoStart) 1 else 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun streamNotification(context: Context, state: StreamUiState): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            context, 0,
            Intent(context, StreamService::class.java).setAction(StreamService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detail = buildString {
            append(state.phase.label)
            state.nextRetryInSeconds?.let { append(" em ${it}s") }
            if (state.bitrateKbps > 0) append(" · ${state.bitrateKbps} kbps")
            state.device.batteryTemperatureC?.let { append(" · %.1f °C".format(it)) }
        }
        return NotificationCompat.Builder(context, CHANNEL_STREAM)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(context.getString(R.string.notification_streaming_title))
            .setContentText(detail)
            .setOngoing(true)                 // usuário não remove arrastando
            .setOnlyAlertOnce(true)           // atualizações não tocam som de novo
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.action_stop), stopIntent)
            .build()
    }

    fun showTemperatureWarning(context: Context, temperatureC: Float, limitC: Int) {
        notify(
            context, ID_TEMPERATURE,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_camera)
                .setContentTitle(context.getString(R.string.temperature_warning_title))
                .setContentText(context.getString(R.string.temperature_warning_text, temperatureC, limitC))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.temperature_warning_text, temperatureC, limitC)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancelTemperatureWarning(context: Context) =
        NotificationManagerCompat.from(context).cancel(ID_TEMPERATURE)

    /** Quando o Android impede o início em segundo plano: um toque do usuário destrava. */
    fun showTapToStart(context: Context, reason: String) {
        notify(
            context, ID_START_BLOCKED,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_camera)
                .setContentTitle(context.getString(R.string.tap_to_start_title))
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openAppIntent(context, autoStart = true))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancelTapToStart(context: Context) =
        NotificationManagerCompat.from(context).cancel(ID_START_BLOCKED)

    fun notify(context: Context, id: Int, notification: android.app.Notification) {
        val manager = NotificationManagerCompat.from(context)
        // Android 13+: sem a permissão POST_NOTIFICATIONS a chamada seria ignorada/lançaria exceção.
        if (manager.areNotificationsEnabled()) {
            try {
                manager.notify(id, notification)
            } catch (e: SecurityException) {
                // Permissão revogada entre a checagem e a chamada; nada a fazer.
            }
        }
    }
}
