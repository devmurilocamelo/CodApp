package dev.oldcam.camera.stream

import java.net.URI
import java.net.URISyntaxException
import java.text.Normalizer

/**
 * Monta e valida o endereço de publicação: `<servidor>/<path-da-câmera>`.
 *
 * Fica em Kotlin "puro" (sem classes do Android) de propósito: assim dá para
 * testar com JUnit comum no PC, sem emulador (ver StreamEndpointTest).
 *
 * As credenciais NUNCA entram na URL: vão separadas para o RootEncoder
 * (setAuthorization). URL com senha acaba em logs, notificações e prints.
 */
data class StreamEndpoint(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
) {
    /** RTSPS = RTSP dentro de TLS (cifrado). Obrigatório quando o servidor está na internet. */
    val isSecure: Boolean get() = scheme == SCHEME_RTSPS

    /** URL final entregue ao RootEncoder. Ex.: rtsps://cam.exemplo.com:8322/garagem */
    val url: String get() = "$scheme://${hostForUrl()}:$port/$path"

    private fun hostForUrl() = if (host.contains(':')) "[$host]" else host // IPv6 precisa de colchetes

    companion object {
        const val SCHEME_RTSP = "rtsp"
        const val SCHEME_RTSPS = "rtsps"
        /** Portas padrão do MediaMTX (não as do RFC, 554/322). */
        const val DEFAULT_RTSP_PORT = 8554
        const val DEFAULT_RTSPS_PORT = 8322

        private val PATH_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

        /**
         * Converte um nome livre em path seguro para URL: "Garagem Frente" → "garagem-frente".
         * Remove acentos (Normalizer + regex) e qualquer caractere fora de [a-z0-9_-].
         */
        fun slugify(name: String): String {
            val noAccents = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            return noAccents.lowercase()
                .replace(Regex("[^a-z0-9_-]+"), "-")
                .replace(Regex("-{2,}"), "-")
                .trim('-', '_')
                .take(64)
        }

        /**
         * Valida as entradas e devolve um [Result]: sucesso com o endpoint, ou falha
         * com uma mensagem amigável em português.
         *
         * `Result<T>` é a forma idiomática do Kotlin de devolver "deu certo OU deu erro"
         * sem lançar exceção para quem chama.
         */
        fun parse(serverUrl: String, cameraPath: String): Result<StreamEndpoint> {
            val raw = serverUrl.trim().trimEnd('/')
            if (raw.isEmpty()) return failure("Informe a URL do servidor.")

            val uri = try {
                URI(raw)
            } catch (e: URISyntaxException) {
                return failure("URL do servidor inválida.")
            }

            val scheme = uri.scheme?.lowercase()
                ?: return failure("A URL precisa começar com rtsps:// ou rtsp://")
            if (scheme != SCHEME_RTSP && scheme != SCHEME_RTSPS) {
                return failure("Protocolo não suportado: use rtsps:// (recomendado) ou rtsp://")
            }
            if (uri.rawUserInfo != null) {
                return failure("Não coloque usuário/senha na URL; use os campos próprios.")
            }
            val host = uri.host?.removeSurrounding("[", "]")
            if (host.isNullOrBlank()) return failure("URL sem host. Ex.: rtsps://cam.seudominio.com:8322")
            if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) {
                return failure("Informe só o servidor (sem caminho); o path vem do nome da câmera.")
            }

            val port = if (uri.port == -1) {
                if (scheme == SCHEME_RTSPS) DEFAULT_RTSPS_PORT else DEFAULT_RTSP_PORT
            } else {
                uri.port
            }

            val path = cameraPath.trim()
            if (!PATH_REGEX.matches(path)) {
                return failure("Nome da câmera inválido: use letras minúsculas, números, - ou _.")
            }

            return Result.success(StreamEndpoint(scheme, host, port, path))
        }

        private fun failure(message: String): Result<StreamEndpoint> =
            Result.failure(IllegalArgumentException(message))
    }
}
