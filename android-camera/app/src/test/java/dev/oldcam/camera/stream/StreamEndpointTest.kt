package dev.oldcam.camera.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes de JVM pura: rodam com `./gradlew test`, sem emulador. */
class StreamEndpointTest {

    @Test
    fun `rtsps sem porta usa 8322 do MediaMTX`() {
        val ep = StreamEndpoint.parse("rtsps://cam.exemplo.com", "garagem").getOrThrow()
        assertEquals("rtsps://cam.exemplo.com:8322/garagem", ep.url)
        assertTrue(ep.isSecure)
    }

    @Test
    fun `rtsp sem porta usa 8554 e nao e seguro`() {
        val ep = StreamEndpoint.parse("RTSP://192.168.0.10/", "sala").getOrThrow()
        assertEquals("rtsp://192.168.0.10:8554/sala", ep.url)
        assertFalse(ep.isSecure)
    }

    @Test
    fun `porta explicita e preservada`() {
        val ep = StreamEndpoint.parse("rtsps://cam.exemplo.com:443", "a").getOrThrow()
        assertEquals(443, ep.port)
    }

    @Test
    fun `ipv6 recebe colchetes`() {
        val ep = StreamEndpoint.parse("rtsp://[fd00::1]:8554", "x").getOrThrow()
        assertEquals("rtsp://[fd00::1]:8554/x", ep.url)
    }

    @Test
    fun `credenciais na URL sao recusadas`() {
        assertTrue(StreamEndpoint.parse("rtsps://user:senha@cam.exemplo.com", "a").isFailure)
    }

    @Test
    fun `protocolos diferentes de rtsp sao recusados`() {
        assertTrue(StreamEndpoint.parse("http://cam.exemplo.com", "a").isFailure)
        assertTrue(StreamEndpoint.parse("rtmp://cam.exemplo.com", "a").isFailure)
        assertTrue(StreamEndpoint.parse("cam.exemplo.com", "a").isFailure)
    }

    @Test
    fun `caminho na URL do servidor e recusado`() {
        assertTrue(StreamEndpoint.parse("rtsps://cam.exemplo.com/live", "a").isFailure)
    }

    @Test
    fun `path invalido e recusado`() {
        assertTrue(StreamEndpoint.parse("rtsps://cam.exemplo.com", "").isFailure)
        assertTrue(StreamEndpoint.parse("rtsps://cam.exemplo.com", "Com Espaço").isFailure)
        assertTrue(StreamEndpoint.parse("rtsps://cam.exemplo.com", "../etc").isFailure)
    }

    @Test
    fun `slugify remove acentos e espacos`() {
        assertEquals("garagem-frente", StreamEndpoint.slugify("  Garagem Frente "))
        assertEquals("cozinha", StreamEndpoint.slugify("Cozinha!!"))
        assertEquals("portao-sao-joao", StreamEndpoint.slugify("Portão São João"))
        assertEquals("", StreamEndpoint.slugify("***"))
    }
}
