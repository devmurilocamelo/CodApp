package dev.oldcam.camera.settings

/**
 * Tudo que o usuário configura na tela de ajustes.
 *
 * `data class` em Kotlin = classe só de dados. O compilador gera sozinho
 * equals(), hashCode(), toString() e copy(). O `copy()` é útil para criar
 * uma versão alterada sem modificar a original (objetos imutáveis).
 */
data class CameraSettings(
    /** Base do servidor, sem caminho. Ex.: rtsps://cam.meudominio.com:8322 */
    val serverUrl: String = "",
    /** Path no MediaMTX (vira o final da URL). Ex.: "garagem" */
    val cameraPath: String = "",
    val publishUser: String = "",
    /** Guardada cifrada no disco; aqui em memória ela fica em texto puro. */
    val publishPassword: String = "",
    val resolution: Resolution = Resolution.P720,
    val fps: Int = 15,
    val facing: Facing = Facing.BACK,
    val orientation: Orientation = Orientation.LANDSCAPE,
    /** Acima desta temperatura (°C) da bateria o app avisa o usuário. */
    val temperatureLimitC: Int = 45,
    val autoStartOnBoot: Boolean = false,
) {
    /** `get() =` define uma propriedade calculada: recalculada a cada leitura. */
    val isComplete: Boolean
        get() = serverUrl.isNotBlank() && cameraPath.isNotBlank() &&
            publishUser.isNotBlank() && publishPassword.isNotBlank()

    /**
     * toString() gerado pela data class imprimiria a senha. Sobrescrevemos para que
     * um log acidental nunca vaze credenciais.
     */
    override fun toString(): String =
        "CameraSettings(serverUrl=$serverUrl, cameraPath=$cameraPath, publishUser=$publishUser, " +
            "publishPassword=***, resolution=$resolution, fps=$fps, facing=$facing, " +
            "orientation=$orientation, temperatureLimitC=$temperatureLimitC, autoStartOnBoot=$autoStartOnBoot)"

    companion object {
        /** FPS oferecidos na tela. Câmera de segurança não precisa de 30; 15 economiza banda e calor. */
        val FPS_OPTIONS = listOf(10, 15, 20, 25, 30)
        const val MIN_TEMPERATURE_LIMIT = 35
        const val MAX_TEMPERATURE_LIMIT = 60
    }
}

/**
 * `enum class` = conjunto fechado de valores. Cada valor pode carregar dados
 * (aqui: largura, altura e bitrate alvo).
 *
 * Larguras múltiplas de 16 (848 em vez de 854) evitam bugs em encoders de hardware antigos.
 */
enum class Resolution(val label: String, val width: Int, val height: Int, val bitrateBps: Int) {
    P480("480p", 848, 480, 1_000_000),
    P720("720p", 1280, 720, 2_000_000),
}

enum class Facing { BACK, FRONT }

enum class Orientation { LANDSCAPE, PORTRAIT }
