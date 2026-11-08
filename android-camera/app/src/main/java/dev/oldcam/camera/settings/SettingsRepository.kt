package dev.oldcam.camera.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Lê e grava [CameraSettings] em SharedPreferences (um arquivo XML privado do app).
 *
 * A senha é a única coisa sensível e passa pelo [SecretCipher] antes de ir para o disco.
 *
 * `private val prefs = ...` no construtor primário: o Kotlin cria o campo e já o
 * inicializa — equivale a declarar e atribuir no construtor do Java/PHP.
 */
class SettingsRepository(context: Context) {

    // applicationContext evita "vazar" uma Activity (ela pode ser destruída antes deste objeto).
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CameraSettings {
        val defaults = CameraSettings()
        return CameraSettings(
            serverUrl = prefs.getString(KEY_SERVER_URL, defaults.serverUrl).orEmpty(),
            cameraPath = prefs.getString(KEY_CAMERA_PATH, defaults.cameraPath).orEmpty(),
            publishUser = prefs.getString(KEY_USER, defaults.publishUser).orEmpty(),
            publishPassword = SecretCipher.decrypt(prefs.getString(KEY_PASSWORD_ENC, "").orEmpty()),
            // runCatching { } transforma exceção em resultado: valor antigo inválido → padrão.
            resolution = runCatching { Resolution.valueOf(prefs.getString(KEY_RESOLUTION, null)!!) }
                .getOrDefault(defaults.resolution),
            fps = prefs.getInt(KEY_FPS, defaults.fps),
            facing = runCatching { Facing.valueOf(prefs.getString(KEY_FACING, null)!!) }
                .getOrDefault(defaults.facing),
            orientation = runCatching { Orientation.valueOf(prefs.getString(KEY_ORIENTATION, null)!!) }
                .getOrDefault(defaults.orientation),
            temperatureLimitC = prefs.getInt(KEY_TEMP_LIMIT, defaults.temperatureLimitC),
            autoStartOnBoot = prefs.getBoolean(KEY_AUTOSTART, defaults.autoStartOnBoot),
        )
    }

    fun save(settings: CameraSettings) {
        // `edit { }` (core-ktx) abre um editor, roda o bloco e chama apply() (gravação assíncrona).
        prefs.edit {
            putString(KEY_SERVER_URL, settings.serverUrl.trim())
            putString(KEY_CAMERA_PATH, settings.cameraPath.trim())
            putString(KEY_USER, settings.publishUser.trim())
            putString(KEY_PASSWORD_ENC, SecretCipher.encrypt(settings.publishPassword))
            putString(KEY_RESOLUTION, settings.resolution.name)
            putInt(KEY_FPS, settings.fps)
            putString(KEY_FACING, settings.facing.name)
            putString(KEY_ORIENTATION, settings.orientation.name)
            putInt(KEY_TEMP_LIMIT, settings.temperatureLimitC)
            putBoolean(KEY_AUTOSTART, settings.autoStartOnBoot)
        }
    }

    /** Lembra se o usuário deixou o stream ligado — usado para religar após boot/atualização. */
    var streamWanted: Boolean
        get() = prefs.getBoolean(KEY_STREAM_WANTED, false)
        set(value) = prefs.edit { putBoolean(KEY_STREAM_WANTED, value) }

    // companion object = membros "estáticos" da classe.
    private companion object {
        const val PREFS_NAME = "oldcam_settings"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_CAMERA_PATH = "camera_path"
        const val KEY_USER = "publish_user"
        const val KEY_PASSWORD_ENC = "publish_password_enc"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FPS = "fps"
        const val KEY_FACING = "facing"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_TEMP_LIMIT = "temperature_limit_c"
        const val KEY_AUTOSTART = "autostart_on_boot"
        const val KEY_STREAM_WANTED = "stream_wanted"
    }
}
