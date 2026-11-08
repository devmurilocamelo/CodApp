# CLAUDE.md — OldCam

Sistema DIY de câmeras de segurança: celulares Android antigos viram câmeras IP que publicam
para um MediaMTX num VPS, e um painel Laravel faz a autenticação e serve ao vivo e as gravações.

## Estrutura (monorepo)

| Pasta             | O quê                                               | Fase |
|-------------------|-----------------------------------------------------|------|
| `android-camera/` | App Kotlin: CameraX → H.264 → RTSP(S) → MediaMTX    | 1 ✅ |
| `media-server/`   | MediaMTX via Docker Compose (`dev/` = teste da F1)  | 2    |
| `web/`            | Laravel + Inertia + React (painel, proxy autenticado) | 3  |
| `docs/`           | Arquitetura, guias de instalação, checklist de segurança | todas |

## Forma de trabalho

- Uma fase por vez. Antes de codar: plano + estrutura de pastas. Commit ao fim de cada fase.
  **Só avançar de fase com aprovação explícita do usuário.**
- Ao fim de cada fase, documentar: como testar, pendências e riscos conhecidos (`docs/fase-N-*.md`).
- Idioma: código (identificadores) em inglês, comentários, UI e docs em **português**.
- O usuário conhece Laravel/React/PHP e está começando em Kotlin: **comente o código Android
  de forma didática** (explique construções do Kotlin e APIs do Android quando aparecerem).

## Regras de segurança (inegociáveis)

1. Nada exposto sem autenticação. O MediaMTX tem usuários separados para **publicar** e **ler**.
2. Segredos só em `.env` (gitignored), com `.env.example` versionado. Nunca hardcoded.
3. O navegador **nunca** recebe credenciais do MediaMTX: o Laravel faz proxy/autorização (Fase 3).
4. Internet = TLS. Câmeras publicam em `rtsps://` com verificação de hostname. `rtsp://` puro só na LAN
   (o app mostra aviso).
5. Credenciais nunca dentro de URLs (vão em campos/cabeçalhos próprios) e nunca em logs/`toString()`.
6. No Android: senha cifrada com AES-GCM via Android Keystore; `allowBackup=false`; componentes
   `exported=false` exceto o que o sistema precisa (launcher, boot receiver).

## Decisões tomadas

| Decisão | Motivo |
|---|---|
| minSdk 26 (Android 8.0), targetSdk 36, compileSdk 37 | Cobre celulares antigos; target 36 até validarmos as mudanças de comportamento do Android 17 |
| Servidor = VPS Linux com domínio | Escolha do usuário. Implica TLS em tudo e hardening forte (Fase 4) |
| RootEncoder `RtspStream` + `CameraXSource` (extra-sources) | `RtspStreamClient` expõe `setTlsHostVerification`; o `GenericStream` não |
| Socket `SocketType.JAVA` + `setTlsHostVerification(true)` | Valida cadeia **e** hostname do certificado (o modo Ktor só usa SNI) |
| RTSP sobre **TCP** | Atravessa NAT/firewall do VPS; sem perda de pacote |
| Só vídeo (`NoAudioSource` + `setOnlyVideo`) | Privacidade/legislação, menos banda e permissões. Áudio = pendência opcional |
| Reconexão própria (`ReconnectPolicy`), `setReTries(0)` na lib | Backoff exponencial 1 s→60 s com jitter ±20%; retry imediato quando a rede volta |
| Boot no Android 10+ via Activity (permissão "Sobrepor a outros apps") ou notificação "toque para iniciar" | Android proíbe abrir câmera em segundo plano e (15+) iniciar FGS `camera` direto do BOOT_COMPLETED |
| GOP de 2 s (`iFrameInterval=2`) | MediaMTX corta segmentos de gravação em keyframes |
| Resoluções 848×480 (1 Mbps) e 1280×720 (2 Mbps), FPS padrão 15 | Largura múltipla de 16 evita bugs de encoder antigo; 15 fps basta para CFTV |
| Portas MediaMTX: RTSP 8554, RTSPS 8322 | Padrões do MediaMTX; o app completa a porta se omitida |
| Path da câmera = slug do nome (`[a-z0-9_-]{1,64}`) | URL segura e previsível; mesmo identificador no painel |
| `androidx.security-crypto` **não** usado | Descontinuado; `SecretCipher` usa o Keystore direto |
| Kotlin embutido do AGP 9 (sem aplicar `kotlin-android`) | Padrão do AGP 9; o plugin só é declarado na raiz para fixar a versão |

## Versões (verificadas em nov/2026)

Android: AGP 9.5.1 · Gradle 9.9.0 · Kotlin 2.4.21 · RootEncoder 2.8.1 (JitPack) · CameraX 1.6.2 ·
core-ktx 1.19.1 · appcompat 1.8.0 · activity 1.13.0 · lifecycle 2.11.0 · material 1.14.0 ·
constraintlayout 2.2.2 · coroutines 1.11.0 · JUnit 4.13.2.
Servidor: MediaMTX 1.21.3 (`bluenviron/mediamtx:1.21.3`). Versões ficam em
`android-camera/gradle/libs.versions.toml` — não espalhe versões pelos `build.gradle.kts`.

## Comandos

```bash
# Android (precisa de JDK 17+ e Android SDK; ANDROID_HOME ou android-camera/local.properties)
cd android-camera
./gradlew testDebugUnitTest   # testes JVM (StreamEndpoint, ReconnectPolicy, TemperatureGuard)
./gradlew lintDebug           # lint (abortOnError=true)
./gradlew assembleDebug       # APK em app/build/outputs/apk/debug/

# Servidor de teste da Fase 1
cd media-server/dev && cp .env.example .env && docker compose up
```

## Convenções Android

- Pacote `dev.oldcam.camera`, subpacotes por responsabilidade: `settings/`, `stream/`, `monitor/`, `boot/`, `ui/`.
- Lógica sem dependência do Android (validação, backoff, histerese) em classes Kotlin puras com testes JVM.
- Estado compartilhado serviço → UI via `StreamStatus` (`StateFlow`). A Activity nunca transmite.
- ViewBinding (sem `findViewById`), Material 3, strings em `res/values/strings.xml`.
- Callbacks do RootEncoder chegam na main thread; CameraX também exige a main thread.
