package dev.oldcam.camera.stream

import kotlin.math.min
import kotlin.random.Random

/**
 * Backoff exponencial com "jitter" para reconectar.
 *
 * Tentativa 1 espera ~1 s, depois ~2 s, ~4 s, ~8 s ... até o teto (60 s).
 * Por que exponencial? Se o servidor caiu, ficar batendo a cada segundo só gasta
 * bateria e dados. Por que jitter (aleatoriedade)? Se 5 câmeras caem juntas (queda
 * de energia), elas não voltam todas no mesmo milissegundo sobrecarregando o servidor.
 *
 * Sem dependências do Android → testável com JUnit puro (ReconnectPolicyTest).
 *
 * `private val random: Random = Random.Default` é um parâmetro com valor padrão:
 * no app usamos o aleatório real; no teste injetamos um previsível.
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 60_000,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) {
    /** Quantas falhas seguidas já aconteceram. `private set` = só esta classe altera. */
    var attempt: Int = 0
        private set

    /** Calcula a espera da próxima tentativa e avança o contador. */
    fun nextDelayMs(): Long {
        // 1 shl n = 2^n. `coerceAtMost(20)` evita overflow em sequências muito longas de falhas.
        val exponential = baseDelayMs * (1L shl attempt.coerceAtMost(20))
        val capped = min(exponential, maxDelayMs)
        attempt++
        // Jitter de ±20%: delay * (0.8 .. 1.2)
        val factor = 1.0 + (random.nextDouble() * 2 - 1) * jitterRatio
        return (capped * factor).toLong().coerceIn(baseDelayMs / 2, maxDelayMs)
    }

    /** Usado para erro de autenticação: não adianta insistir rápido. */
    fun maxDelay(): Long {
        attempt++
        return maxDelayMs
    }

    /** Chamar quando conectar com sucesso. */
    fun reset() {
        attempt = 0
    }
}
