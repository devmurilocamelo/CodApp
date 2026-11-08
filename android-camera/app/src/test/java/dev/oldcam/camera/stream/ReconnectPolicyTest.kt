package dev.oldcam.camera.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {

    /** Random "fixo" que sempre devolve 0.5 → fator de jitter = 1.0 (sem variação). */
    private val noJitter = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextDouble() = 0.5
    }

    @Test
    fun `delay dobra a cada tentativa ate o teto`() {
        val policy = ReconnectPolicy(baseDelayMs = 1_000, maxDelayMs = 60_000, random = noJitter)
        val delays = List(8) { policy.nextDelayMs() }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000), delays)
    }

    @Test
    fun `reset volta ao delay inicial`() {
        val policy = ReconnectPolicy(random = noJitter)
        repeat(5) { policy.nextDelayMs() }
        policy.reset()
        assertEquals(0, policy.attempt)
        assertEquals(1_000L, policy.nextDelayMs())
    }

    @Test
    fun `jitter fica dentro de 20 por cento e do teto`() {
        val policy = ReconnectPolicy(random = Random(42))
        repeat(200) {
            val d = policy.nextDelayMs()
            assertTrue("delay $d fora do limite", d in 500..60_000)
        }
    }

    @Test
    fun `muitas falhas nao causam overflow`() {
        val policy = ReconnectPolicy(random = noJitter)
        repeat(10_000) { policy.nextDelayMs() }
        assertEquals(60_000L, policy.nextDelayMs())
    }
}
