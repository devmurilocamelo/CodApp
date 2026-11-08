package dev.oldcam.camera.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Fases do stream mostradas na tela e na notificação. */
enum class StreamPhase(val label: String) {
    STOPPED("Parado"),
    CONNECTING("Conectando…"),
    CONNECTED("Conectado"),
    RECONNECTING("Reconectando"),
    AUTH_ERROR("Usuário/senha recusados"),
    ERROR("Erro"),
}

/** Leituras do aparelho (bateria/temperatura). `null` = ainda não lido. */
data class DeviceReading(
    val batteryLevelPercent: Int? = null,
    val batteryTemperatureC: Float? = null,
    val isCharging: Boolean = false,
    val overheated: Boolean = false,
)

data class StreamUiState(
    val phase: StreamPhase = StreamPhase.STOPPED,
    val message: String? = null,
    /** URL sem credenciais — seguro de exibir. */
    val endpointUrl: String? = null,
    val isSecure: Boolean = false,
    val bitrateKbps: Long = 0,
    val nextRetryInSeconds: Int? = null,
    val device: DeviceReading = DeviceReading(),
)

/**
 * "Quadro de avisos" global do processo: o [StreamService] escreve, a tela lê.
 *
 * StateFlow (Kotlin Coroutines) é um valor observável: sempre tem um valor atual e
 * avisa quem estiver coletando quando muda. Equivale a um store/estado global do
 * React (pense em Zustand/Redux), mas com tipagem forte.
 *
 * Usamos o padrão "Mutable privado + público só leitura" para que só o app interno
 * altere o estado.
 */
object StreamStatus {
    private val _state = MutableStateFlow(StreamUiState())
    val state: StateFlow<StreamUiState> = _state.asStateFlow()

    /** `update {}` aplica a alteração de forma atômica (segura entre threads). */
    fun update(transform: (StreamUiState) -> StreamUiState) = _state.update(transform)

    fun updateDevice(reading: DeviceReading) = _state.update { it.copy(device = reading) }
}
