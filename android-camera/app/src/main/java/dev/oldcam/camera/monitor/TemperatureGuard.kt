package dev.oldcam.camera.monitor

/**
 * Decide quando avisar sobre superaquecimento, com **histerese**: dispara ao passar do
 * limite e só "rearma" quando a temperatura cair [hysteresisC] graus abaixo dele.
 * Sem isso, uma bateria oscilando entre 44,9 °C e 45,1 °C geraria dezenas de avisos.
 *
 * Kotlin puro → testado em TemperatureGuardTest.
 */
class TemperatureGuard(
    var limitC: Int,
    private val hysteresisC: Float = 2f,
) {
    enum class Event { NONE, OVERHEATED, RECOVERED }

    var overheated: Boolean = false
        private set

    fun onReading(temperatureC: Float): Event = when {
        !overheated && temperatureC >= limitC -> {
            overheated = true
            Event.OVERHEATED
        }
        overheated && temperatureC <= limitC - hysteresisC -> {
            overheated = false
            Event.RECOVERED
        }
        else -> Event.NONE
    }
}
