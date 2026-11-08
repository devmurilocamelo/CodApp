package dev.oldcam.camera.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.oldcam.camera.Notifications
import dev.oldcam.camera.R
import dev.oldcam.camera.settings.SettingsRepository
import dev.oldcam.camera.stream.StreamService
import dev.oldcam.camera.ui.MainActivity

/**
 * Recebe avisos do sistema: celular ligou (BOOT_COMPLETED) ou o app foi atualizado
 * (MY_PACKAGE_REPLACED), e religa a câmera se o usuário quiser.
 *
 * ## A pegadinha do Android 10+
 * Por privacidade, um app em segundo plano **não pode abrir a câmera**, mesmo com um
 * Foreground Service — e o Android 15 proíbe de vez iniciar um serviço do tipo `camera`
 * direto do BOOT_COMPLETED. A saída oficial é: abrir uma tela (Activity), e ela inicia o
 * serviço enquanto está visível. Abrir tela a partir do segundo plano, por sua vez, só é
 * permitido se o usuário concedeu "Sobrepor a outros apps" (SYSTEM_ALERT_WINDOW).
 *
 * Então:
 * - Android 8/9: inicia o serviço direto.
 * - Android 10+ com permissão de sobreposição: abre a MainActivity em modo autostart.
 * - Android 10+ sem a permissão: mostra notificação "Toque para iniciar a câmera".
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Receiver exportado: qualquer app poderia nos mandar um Intent. Só aceitamos estas actions
        // (BOOT_COMPLETED só pode ser enviado pelo sistema).
        val repository = SettingsRepository(context)
        val shouldStart = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> repository.load().autoStartOnBoot
            Intent.ACTION_MY_PACKAGE_REPLACED -> repository.streamWanted
            else -> false
        }
        if (!shouldStart) return
        Log.i(TAG, "Auto-start requested by ${intent.action}")

        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> StreamService.start(context)

            Settings.canDrawOverlays(context) -> context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true),
            )

            else -> Notifications.showTapToStart(
                context, context.getString(R.string.start_blocked_boot),
            )
        }
    }

    private companion object {
        const val TAG = "OldCamBoot"
    }
}
