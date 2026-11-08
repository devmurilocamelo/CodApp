package dev.oldcam.camera.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import dev.oldcam.camera.R
import dev.oldcam.camera.databinding.ActivitySettingsBinding
import dev.oldcam.camera.settings.CameraSettings
import dev.oldcam.camera.settings.Facing
import dev.oldcam.camera.settings.Orientation
import dev.oldcam.camera.settings.Resolution
import dev.oldcam.camera.settings.SettingsRepository
import dev.oldcam.camera.stream.StreamEndpoint
import dev.oldcam.camera.stream.StreamPhase
import dev.oldcam.camera.stream.StreamService
import dev.oldcam.camera.stream.StreamStatus

/**
 * Formulário de configuração. Valida antes de salvar e, se o stream estiver rodando,
 * reinicia o serviço para aplicar os novos valores.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: SettingsRepository

    // Mapas "valor → id do Chip" para converter seleção da tela ↔ configuração.
    private val resolutionChipIds = mutableMapOf<Resolution, Int>()
    private val fpsChipIds = mutableMapOf<Int, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = SettingsRepository(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        buildChips()
        fill(repository.load())

        // Mostra, enquanto digita, qual path o nome vai gerar no servidor.
        binding.cameraNameInput.doAfterTextChanged { updatePathHelper() }
        binding.temperatureSlider.addOnChangeListener { _, value, _ -> updateTemperatureLabel(value.toInt()) }
        binding.saveButton.setOnClickListener { save() }
    }

    /** Cria um Chip para cada opção de resolução e FPS (em vez de repetir XML). */
    private fun buildChips() {
        Resolution.entries.forEach { res ->
            val chip = newChip(res.label)
            binding.resolutionGroup.addView(chip)
            resolutionChipIds[res] = chip.id
        }
        CameraSettings.FPS_OPTIONS.forEach { fps ->
            val chip = newChip(fps.toString())
            binding.fpsGroup.addView(chip)
            fpsChipIds[fps] = chip.id
        }
    }

    private fun newChip(text: String) = Chip(this).apply {
        id = android.view.View.generateViewId()
        this.text = text
        isCheckable = true
    }

    private fun fill(s: CameraSettings) = with(binding) {
        // `with(x) { }` deixa usar as propriedades de x sem repetir "binding." toda hora.
        serverUrlInput.setText(s.serverUrl)
        cameraNameInput.setText(s.cameraPath)
        userInput.setText(s.publishUser)
        passwordInput.setText(s.publishPassword)
        resolutionChipIds[s.resolution]?.let { resolutionGroup.check(it) }
        (fpsChipIds[s.fps] ?: fpsChipIds[15])?.let { fpsGroup.check(it) }
        facingGroup.check(if (s.facing == Facing.FRONT) R.id.facingFront else R.id.facingBack)
        orientationGroup.check(
            if (s.orientation == Orientation.PORTRAIT) R.id.orientationPortrait else R.id.orientationLandscape,
        )
        val limit = s.temperatureLimitC.coerceIn(
            CameraSettings.MIN_TEMPERATURE_LIMIT, CameraSettings.MAX_TEMPERATURE_LIMIT,
        )
        temperatureSlider.value = limit.toFloat()
        updateTemperatureLabel(limit)
        autostartSwitch.isChecked = s.autoStartOnBoot
        updatePathHelper()
    }

    private fun updatePathHelper() {
        val slug = StreamEndpoint.slugify(binding.cameraNameInput.text?.toString().orEmpty())
        binding.cameraNameLayout.helperText =
            getString(R.string.helper_camera_path, slug.ifEmpty { "…" })
    }

    private fun updateTemperatureLabel(value: Int) {
        binding.temperatureLimitLabel.text = getString(R.string.label_temperature_limit, value)
    }

    private fun save() = with(binding) {
        // Limpa erros anteriores.
        listOf(serverUrlLayout, cameraNameLayout, userLayout, passwordLayout).forEach { it.error = null }

        val serverUrl = serverUrlInput.text?.toString()?.trim().orEmpty()
        val path = StreamEndpoint.slugify(cameraNameInput.text?.toString().orEmpty())
        val user = userInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()

        var valid = true
        StreamEndpoint.parse(serverUrl, path).onFailure { error ->
            valid = false
            // Decide qual campo marcar pelo conteúdo da validação.
            if (path.isEmpty() || error.message?.contains("câmera") == true) {
                cameraNameLayout.error = error.message
            } else {
                serverUrlLayout.error = error.message
            }
        }
        if (user.isEmpty()) { userLayout.error = getString(R.string.error_required); valid = false }
        if (password.isEmpty()) { passwordLayout.error = getString(R.string.error_required); valid = false }
        if (!valid) return@with

        // `entries.first { }` procura o enum cujo chip está marcado.
        val resolution = Resolution.entries.first { resolutionChipIds[it] == resolutionGroup.checkedChipId }
        val fps = CameraSettings.FPS_OPTIONS.first { fpsChipIds[it] == fpsGroup.checkedChipId }

        repository.save(
            CameraSettings(
                serverUrl = serverUrl,
                cameraPath = path,
                publishUser = user,
                publishPassword = password,
                resolution = resolution,
                fps = fps,
                facing = if (facingGroup.checkedButtonId == R.id.facingFront) Facing.FRONT else Facing.BACK,
                orientation = if (orientationGroup.checkedButtonId == R.id.orientationPortrait) {
                    Orientation.PORTRAIT
                } else {
                    Orientation.LANDSCAPE
                },
                temperatureLimitC = temperatureSlider.value.toInt(),
                autoStartOnBoot = autostartSwitch.isChecked,
            ),
        )

        val phase = StreamStatus.state.value.phase
        val running = phase != StreamPhase.STOPPED && phase != StreamPhase.ERROR
        if (running) StreamService.restart(this@SettingsActivity)
        Toast.makeText(
            this@SettingsActivity,
            if (running) R.string.saved_restarting else R.string.saved,
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }
}
