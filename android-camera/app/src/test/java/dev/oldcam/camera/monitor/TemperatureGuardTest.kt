package dev.oldcam.camera.monitor

import dev.oldcam.camera.monitor.TemperatureGuard.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class TemperatureGuardTest {

    @Test
    fun `avisa uma vez e so rearma abaixo da histerese`() {
        val guard = TemperatureGuard(limitC = 45, hysteresisC = 2f)
        val events = listOf(40f, 45f, 45.5f, 44.9f, 43.5f, 43f, 46f).map { guard.onReading(it) }
        assertEquals(
            listOf(Event.NONE, Event.OVERHEATED, Event.NONE, Event.NONE, Event.NONE, Event.RECOVERED, Event.OVERHEATED),
            events,
        )
    }
}
