package dev.oldcam.camera.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.oldcam.camera.R
import dev.oldcam.camera.databinding.ActivityMainBinding
import dev.oldcam.camera.monitor.DeviceMonitor
import dev.oldcam.camera.settings.SettingsRepository
import dev.oldcam.camera.stream.StreamPhase
import dev.oldcam.camera.stream.StreamService
import dev.oldcam.camera.stream.StreamStatus
import dev.oldcam.camera.stream.StreamUiState
import kotlinx.coroutines.launch

/**
 * Tela principal: mostra o status e liga/desliga a câmera.
 *
 * A Activity NÃO transmite nada — quem transmite é o [StreamService]. A tela só observa
 * o [StreamStatus] e manda comandos. Assim fechar a tela não derruba o stream.
 */
class MainActivity : AppCompatActivity() {

    // ViewBinding: classe gerada a partir de res/layout/activity_main.xml.
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: SettingsRepository
    private lateinit var deviceMonitor: DeviceMonitor
    /** Cache do limite: render() roda ~1x/s e load() decifra a senha — não queremos isso a cada frame. */
    private var temperatureLimitC = 45

    /**
     * Forma moderna de pedir permissões: registramos um "launcher" e recebemos o resultado
     * num callback. Precisa ser criado como propriedade (antes de onCreate terminar).
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            StreamService.start(this)
        } else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = SettingsRepository(this)

        // Com o serviço parado, a própria tela lê a bateria para exibir.
        deviceMonitor = DeviceMonitor(this) { reading ->
            if (!isServiceActive(StreamStatus.state.value)) StreamStatus.updateDevice(reading)
        }

        binding.startStopButton.setOnClickListener {
            if (isServiceActive(StreamStatus.state.value)) StreamService.stop(this) else requestStart()
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.batteryOptButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.overlayButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
            )
        }

        // Coleta o estado enquanto a tela estiver visível (STARTED) e para sozinho quando sair.
        // Equivale a um useEffect com subscribe/unsubscribe no React.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StreamStatus.state.collect { render(it) }
            }
        }

        handleAutostart(intent)
    }

    /** singleTask: se a tela já existe, um novo Intent (ex.: toque na notificação) chega aqui. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutostart(intent)
    }

    override fun onStart() {
        super.onStart()
        deviceMonitor.start()
    }

    override fun onResume() {
        super.onResume()
        temperatureLimitC = repository.load().temperatureLimitC
        renderSetupHints() // o usuário pode ter voltado das configurações do sistema
        render(StreamStatus.state.value)
    }

    override fun onStop() {
        deviceMonitor.stop()
        super.onStop()
    }

    /**
     * Aberta pelo BootReceiver ou pela notificação "Toque para iniciar": com a tela visível
     * o Android permite abrir a câmera; iniciamos o serviço e mandamos a tela para trás.
     */
    private fun handleAutostart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) != true) return
        intent.removeExtra(EXTRA_AUTOSTART) // não repetir ao girar a tela
        if (repository.load().isComplete && hasCameraPermission()) {
            StreamService.start(this)
            moveTaskToBack(true)
        } else {
            requestStart()
        }
    }

    private fun requestStart() {
        if (!repository.load().isComplete) {
            Toast.makeText(this, R.string.need_settings, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        // `buildList { }` monta uma lista de forma imperativa e devolve imutável.
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Abre o diálogo do sistema para tirar o app do Doze. Sem isso, muitos aparelhos
     * (Xiaomi, Samsung, Motorola…) cortam a rede do app depois de alguns minutos de tela apagada.
     */
    @SuppressLint("BatteryLife") // permitido: app instalado manualmente, fora da Play Store
    private fun requestIgnoreBatteryOptimizations() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()),
        )
    }

    private fun renderSetupHints() {
        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val needsOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            repository.load().autoStartOnBoot && !Settings.canDrawOverlays(this)

        binding.batteryOptButton.visibility = if (batteryOk) View.GONE else View.VISIBLE
        binding.overlayButton.visibility = if (needsOverlay) View.VISIBLE else View.GONE
        binding.overlayExplain.visibility = binding.overlayButton.visibility
        binding.setupDoneText.visibility = if (batteryOk && !needsOverlay) View.VISIBLE else View.GONE
    }

    private fun render(state: StreamUiState) {
        val color = when (state.phase) {
            StreamPhase.CONNECTED -> R.color.status_ok
            StreamPhase.CONNECTING, StreamPhase.RECONNECTING -> R.color.status_warn
            StreamPhase.AUTH_ERROR, StreamPhase.ERROR -> R.color.status_error
            StreamPhase.STOPPED -> R.color.status_idle
        }
        binding.phaseText.text = state.nextRetryInSeconds
            ?.takeIf { state.phase == StreamPhase.RECONNECTING || state.phase == StreamPhase.AUTH_ERROR }
            ?.let { getString(R.string.retry_in, state.phase.label, it) }
            ?: state.phase.label
        binding.phaseText.setTextColor(ContextCompat.getColor(this, color))

        binding.messageText.text = state.message
        binding.messageText.visibility = if (state.message.isNullOrBlank()) View.GONE else View.VISIBLE

        val active = isServiceActive(state)
        binding.insecureWarning.visibility =
            if (active && state.endpointUrl != null && !state.isSecure) View.VISIBLE else View.GONE
        binding.endpointText.text = state.endpointUrl ?: getString(R.string.value_none)
        binding.bitrateText.text =
            if (state.bitrateKbps > 0) getString(R.string.value_kbps, state.bitrateKbps.toInt())
            else getString(R.string.value_none)

        val device = state.device
        binding.batteryText.text = device.batteryLevelPercent?.let {
            getString(if (device.isCharging) R.string.value_battery_charging else R.string.value_battery, it)
        } ?: getString(R.string.value_none)

        val limit = temperatureLimitC
        val temp = device.batteryTemperatureC
        val hot = device.overheated || (temp != null && temp >= limit)
        binding.temperatureText.text = when {
            temp == null -> getString(R.string.value_none)
            hot -> getString(R.string.value_temperature_hot, temp, limit)
            else -> getString(R.string.value_temperature, temp)
        }
        binding.temperatureText.setTextColor(
            ContextCompat.getColor(this, if (hot) R.color.status_error else R.color.status_idle),
        )

        binding.startStopButton.setText(if (active) R.string.action_stop else R.string.action_start)
    }

    /** STOPPED/ERROR = serviço não está rodando. */
    private fun isServiceActive(state: StreamUiState) =
        state.phase != StreamPhase.STOPPED && state.phase != StreamPhase.ERROR

    companion object {
        const val EXTRA_AUTOSTART = "dev.oldcam.camera.extra.AUTOSTART"
    }
}
