package dev.oldcam.camera.stream

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pedro.common.ConnectChecker
import com.pedro.common.socket.base.SocketType
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.extrasources.CameraXSource
import com.pedro.library.rtsp.RtspStream
import com.pedro.library.util.BitrateAdapter
import com.pedro.rtsp.rtsp.Protocol
import dev.oldcam.camera.Notifications
import dev.oldcam.camera.R
import dev.oldcam.camera.monitor.DeviceMonitor
import dev.oldcam.camera.monitor.TemperatureGuard
import dev.oldcam.camera.settings.CameraSettings
import dev.oldcam.camera.settings.Facing
import dev.oldcam.camera.settings.Orientation
import dev.oldcam.camera.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * O coração do app: captura a câmera, codifica em H.264 e publica via RTSP(S) no MediaMTX.
 *
 * ## Por que um *Foreground Service*?
 * Activities (telas) morrem quando a tela apaga ou o usuário troca de app. Um Service
 * continua rodando; sendo "foreground" (com notificação fixa), o Android quase nunca o mata.
 * No Android 14+ é obrigatório declarar o tipo (`camera`) no manifest e ao chamar startForeground.
 *
 * ## Fluxo
 * ```
 * ACTION_START → enterForeground() → startStreaming() → connect()
 *                                                        │
 *         onConnectionSuccess ◄──────────────────────────┤
 *         onConnectionFailed  → scheduleReconnect() ─────┘ (espera backoff e chama connect de novo)
 * ACTION_STOP  → shutdown() → stopSelf()
 * ```
 *
 * Herdamos de [LifecycleService] para ganhar `lifecycleScope` (coroutines que são canceladas
 * sozinhas quando o serviço morre) e implementamos [ConnectChecker], a interface de callbacks
 * do RootEncoder. Todos os callbacks do RootEncoder chegam na main thread.
 */
class StreamService : LifecycleService(), ConnectChecker {

    // `lateinit var`: promessa ao compilador de que será inicializada antes do uso (em onCreate).
    private lateinit var repository: SettingsRepository
    private lateinit var deviceMonitor: DeviceMonitor
    private val temperatureGuard = TemperatureGuard(limitC = CameraSettings().temperatureLimitC)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectPolicy = ReconnectPolicy()

    // Objetos do RootEncoder. `?` = podem ser null (quando não estamos transmitindo).
    private var stream: RtspStream? = null
    private var cameraSource: CameraXSource? = null
    private var bitrateAdapter: BitrateAdapter? = null
    private var settings = CameraSettings()
    private var endpoint: StreamEndpoint? = null

    private var retryRunnable: Runnable? = null
    private var countdownJob: Job? = null
    /** true quando o stopStream() que fizemos deixou os encoders inutilizáveis. */
    private var needsRebuild = false
    private var userStopped = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ---------------------------------------------------------------------------------
    // Ciclo de vida do Service
    // ---------------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository(this)
        deviceMonitor = DeviceMonitor(this) { reading -> onDeviceReading(reading) }
        deviceMonitor.start()

        // Atualiza a notificação fixa quando o estado muda, no máximo a cada 2 s
        // (o Android limita a frequência de atualização de notificações).
        lifecycleScope.launch {
            StreamStatus.state.collect { state ->
                if (stream != null) {
                    Notifications.notify(
                        this@StreamService, Notifications.ID_STREAM,
                        Notifications.streamNotification(this@StreamService, state),
                    )
                }
                delay(2_000)
            }
        }
    }

    /**
     * Chamado a cada startService/startForegroundService. O valor retornado diz ao sistema
     * o que fazer se ele precisar matar o processo: START_STICKY = "recrie o serviço depois".
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            userStopped = true
            repository.streamWanted = false
            shutdown()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // intent == null significa que o sistema recriou o serviço (START_STICKY).
        if (!enterForeground()) return START_NOT_STICKY

        userStopped = false
        repository.streamWanted = true
        Notifications.cancelTapToStart(this)
        // RESTART: configurações mudaram → derruba tudo e recria com os novos valores.
        if (intent?.action == ACTION_RESTART) shutdown(keepStatus = true)
        if (stream == null) startStreaming()
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        deviceMonitor.stop()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------
    // Foreground + recursos do sistema
    // ---------------------------------------------------------------------------------

    /**
     * Promove o serviço a foreground. Precisa acontecer em até ~5 s após startForegroundService(),
     * senão o Android encerra o app.
     *
     * Pode falhar quando o app está em segundo plano no Android 12+ (ex.: religado pelo sistema
     * sem a tela aberta) ou sem permissão de câmera. Nesse caso mostramos uma notificação
     * "Toque para iniciar" — o toque do usuário é uma exceção aceita pelo Android.
     */
    private fun enterForeground(): Boolean {
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0 // em versões antigas o tipo não existe/é ignorado
        }
        return try {
            if (!hasCamera) throw SecurityException("CAMERA permission not granted")
            ServiceCompat.startForeground(
                this, Notifications.ID_STREAM,
                Notifications.streamNotification(this, StreamStatus.state.value), type,
            )
            true
        } catch (e: Exception) {
            // SecurityException (sem permissão) ou ForegroundServiceStartNotAllowedException (Android 12+).
            Log.w(TAG, "Could not enter foreground", e)
            val reason = getString(
                if (hasCamera) R.string.start_blocked_background else R.string.start_blocked_permission,
            )
            Notifications.showTapToStart(this, reason)
            StreamStatus.update { it.copy(phase = StreamPhase.ERROR, message = reason) }
            stopSelf()
            false
        }
    }

    /**
     * Wake lock PARCIAL: mantém a CPU ligada com a tela apagada (sem ele o encoder "dorme").
     * Wi-Fi lock: impede o Wi-Fi de entrar em modo de economia, que derruba o bitrate.
     *
     * Sem timeout de propósito: a câmera roda 24/7. Os locks são liberados em shutdown().
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OldCam::stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            // HIGH_PERF foi marcado como deprecated, mas continua sendo o único modo que vale
            // com a tela APAGADA (o LOW_LATENCY só atua com o app visível).
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OldCam::wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    /** Quando uma rede volta (Wi-Fi reconectou), tentamos de novo na hora em vez de esperar o backoff. */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Este callback roda numa thread do sistema → voltamos para a main thread.
                mainHandler.post {
                    if (StreamStatus.state.value.phase == StreamPhase.RECONNECTING) {
                        Log.i(TAG, "Network available, retrying now")
                        cancelPendingRetry()
                        connect()
                    }
                }
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        networkCallback = null
    }

    // ---------------------------------------------------------------------------------
    // Stream
    // ---------------------------------------------------------------------------------

    /** Lê as configurações, cria câmera + encoder e dispara a 1ª conexão. */
    private fun startStreaming() {
        settings = repository.load()
        temperatureGuard.limitC = settings.temperatureLimitC

        // `getOrElse { }`: se o Result for falha, executa o bloco (que aqui retorna da função).
        val ep = StreamEndpoint.parse(settings.serverUrl, settings.cameraPath).getOrElse {
            fail(it.message ?: getString(R.string.error_invalid_settings))
            return
        }
        if (!settings.isComplete) {
            fail(getString(R.string.error_incomplete_settings))
            return
        }
        endpoint = ep

        acquireLocks()
        registerNetworkCallback()

        try {
            val source = CameraXSource(applicationContext)
            // NoAudioSource: só vídeo. Menos permissões, menos banda e nada de áudio gravado
            // sem querer (o que pode ser ilegal em alguns lugares).
            val newStream = RtspStream(applicationContext, this, source, NoAudioSource())

            newStream.getStreamClient().apply {
                setAuthorization(settings.publishUser, settings.publishPassword)
                setOnlyVideo(true)
                setProtocol(Protocol.TCP) // TCP atravessa NAT/firewall e não perde pacotes
                // Socket Java + verificação de hostname = TLS "de verdade": o certificado precisa
                // ser válido E pertencer ao domínio da URL. Evita man-in-the-middle no RTSPS.
                setSocketType(SocketType.JAVA)
                setTlsHostVerification(true)
                setReTries(0) // a reconexão é nossa (ReconnectPolicy), não a interna da lib
            }

            val res = settings.resolution
            val rotation = if (settings.orientation == Orientation.PORTRAIT) 90 else 0
            // iFrameInterval=2: um quadro-chave a cada 2 s. O MediaMTX só consegue cortar
            // segmentos de gravação em quadros-chave, então valores baixos = segmentos precisos.
            val prepared = newStream.prepareVideo(
                width = res.width, height = res.height, bitrate = res.bitrateBps,
                fps = settings.fps, iFrameInterval = 2, rotation = rotation,
            ) && newStream.prepareAudio(sampleRate = 32_000, isStereo = false, bitrate = 32_000)
            if (!prepared) {
                newStream.release()
                fail(getString(R.string.error_encoder))
                return
            }

            // Ajusta o bitrate automaticamente se a rede congestionar (e volta a subir depois).
            bitrateAdapter = BitrateAdapter { bitrate -> newStream.setVideoBitrateOnFly(bitrate) }
                .apply { setMaxBitrate(res.bitrateBps) }

            stream = newStream
            cameraSource = source
            needsRebuild = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up camera/encoder", e)
            fail(getString(R.string.error_camera, e.message ?: e.javaClass.simpleName))
            return
        }

        connect()
    }

    private fun connect() {
        if (userStopped) return
        if (needsRebuild) {
            releaseStream()
            startStreaming() // recria tudo e chama connect() de novo
            return
        }
        val s = stream ?: return
        val ep = endpoint ?: return
        if (s.isStreaming) return

        StreamStatus.update {
            it.copy(
                phase = StreamPhase.CONNECTING, message = null, endpointUrl = ep.url,
                isSecure = ep.isSecure, nextRetryInSeconds = null, bitrateKbps = 0,
            )
        }
        try {
            Log.i(TAG, "Connecting to ${ep.url}") // URL sem credenciais: seguro logar
            s.startStream(ep.url)
            applyFacing()
        } catch (e: Exception) {
            Log.e(TAG, "startStream failed", e)
            scheduleReconnect(e.message ?: "startStream failed")
        }
    }

    /** O CameraXSource sempre abre a traseira; trocamos depois que a câmera já está aberta. */
    private fun applyFacing() {
        val source = cameraSource ?: return
        val wantFront = settings.facing == Facing.FRONT
        val isFront = source.getCameraFacing() == CameraSelector.LENS_FACING_FRONT
        if (wantFront != isFront) source.switchCamera()
    }

    /** Para o stream atual e agenda nova tentativa com backoff exponencial. */
    private fun scheduleReconnect(reason: String, authError: Boolean = false) {
        if (userStopped) return
        val s = stream ?: return
        if (s.isStreaming) {
            // stopStream() devolve false se não conseguiu re-preparar os encoders.
            if (!s.stopStream()) needsRebuild = true
        }
        cancelPendingRetry()

        val delayMs = if (authError) reconnectPolicy.maxDelay() else reconnectPolicy.nextDelayMs()
        Log.w(TAG, "Reconnect #${reconnectPolicy.attempt} in ${delayMs}ms: $reason")

        val phase = if (authError) StreamPhase.AUTH_ERROR else StreamPhase.RECONNECTING
        StreamStatus.update { it.copy(phase = phase, message = reason, bitrateKbps = 0) }

        val runnable = Runnable { connect() }
        retryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)

        // Contagem regressiva só para exibição.
        countdownJob = lifecycleScope.launch {
            var remaining = (delayMs / 1000).toInt()
            while (remaining > 0) {
                StreamStatus.update { it.copy(nextRetryInSeconds = remaining) }
                delay(1_000)
                remaining--
            }
        }
    }

    private fun cancelPendingRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        countdownJob?.cancel()
        countdownJob = null
    }

    /** Erro de configuração: não adianta tentar de novo. Mostra a mensagem e encerra. */
    private fun fail(message: String) {
        Log.e(TAG, "Fatal: $message")
        StreamStatus.update { it.copy(phase = StreamPhase.ERROR, message = message, nextRetryInSeconds = null) }
        Notifications.showTapToStart(this, message)
        shutdown(keepStatus = true)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseStream() {
        stream?.let {
            runCatching { if (it.isStreaming) it.stopStream() }
            runCatching { it.release() }
        }
        stream = null
        cameraSource = null
        bitrateAdapter = null
    }

    private fun shutdown(keepStatus: Boolean = false) {
        cancelPendingRetry()
        releaseStream()
        unregisterNetworkCallback()
        releaseLocks()
        reconnectPolicy.reset()
        if (!keepStatus) {
            StreamStatus.update {
                it.copy(phase = StreamPhase.STOPPED, message = null, bitrateKbps = 0, nextRetryInSeconds = null)
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Bateria / temperatura
    // ---------------------------------------------------------------------------------

    private fun onDeviceReading(reading: DeviceReading) {
        val temp = reading.batteryTemperatureC
        if (temp != null) {
            when (temperatureGuard.onReading(temp)) {
                TemperatureGuard.Event.OVERHEATED -> {
                    Log.w(TAG, "Battery temperature $temp°C >= ${temperatureGuard.limitC}°C")
                    Notifications.showTemperatureWarning(this, temp, temperatureGuard.limitC)
                }
                TemperatureGuard.Event.RECOVERED -> Notifications.cancelTemperatureWarning(this)
                TemperatureGuard.Event.NONE -> Unit
            }
        }
        StreamStatus.updateDevice(reading.copy(overheated = temperatureGuard.overheated))
    }

    // ---------------------------------------------------------------------------------
    // Callbacks do RootEncoder (ConnectChecker) — sempre na main thread
    // ---------------------------------------------------------------------------------

    override fun onConnectionStarted(url: String) = Unit

    override fun onConnectionSuccess() {
        Log.i(TAG, "Connected")
        reconnectPolicy.reset()
        bitrateAdapter?.reset()
        StreamStatus.update {
            it.copy(phase = StreamPhase.CONNECTED, message = null, nextRetryInSeconds = null)
        }
    }

    override fun onConnectionFailed(reason: String) {
        scheduleReconnect(reason)
    }

    override fun onAuthError() {
        scheduleReconnect(getString(R.string.error_auth), authError = true)
    }

    override fun onAuthSuccess() = Unit

    /** Chamado quando NÓS desconectamos (stopStream). Quedas reais chegam em onConnectionFailed. */
    override fun onDisconnect() = Unit

    /** A lib informa o bitrate real enviado, ~1x por segundo, em bits/s. */
    override fun onNewBitrate(bitrate: Long) {
        val s = stream ?: return
        bitrateAdapter?.adaptBitrate(bitrate, s.getStreamClient().hasCongestion())
        StreamStatus.update { it.copy(bitrateKbps = bitrate / 1000) }
    }

    companion object {
        private const val TAG = "OldCamStream"
        const val ACTION_START = "dev.oldcam.camera.action.START"
        const val ACTION_STOP = "dev.oldcam.camera.action.STOP"
        const val ACTION_RESTART = "dev.oldcam.camera.action.RESTART"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, StreamService::class.java).setAction(ACTION_START),
            )
        }

        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, StreamService::class.java).setAction(ACTION_RESTART),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StreamService::class.java).setAction(ACTION_STOP))
        }
    }
}
