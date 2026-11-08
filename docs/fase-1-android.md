# Fase 1 — App câmera (Android)

## O que foi entregue

- App `android-camera/` (pacote `dev.oldcam.camera`, minSdk 26).
- Captura com **CameraX** (`CameraXSource` do RootEncoder), encode **H.264 por hardware** e publicação
  **RTSP/RTSPS sobre TCP** com o **RootEncoder 2.8.1** (`RtspStream`).
- **Foreground Service** `StreamService` com notificação fixa (botão "Parar"), `foregroundServiceType="camera"`.
- **Wake lock** parcial + **Wi-Fi lock** enquanto transmite.
- **Reconexão automática** com backoff exponencial (1 s → 60 s, jitter ±20%), retry imediato quando a
  rede volta, espera longa em erro de autenticação, recriação do encoder se necessário.
- **Início no boot** (`BOOT_COMPLETED`) e após atualização do app (`MY_PACKAGE_REPLACED`).
- **Tela de configuração**: URL do servidor, nome da câmera (vira o path), usuário/senha de publicação,
  480p/720p, FPS (10–30), câmera traseira/frontal, orientação, limite de temperatura, início automático.
- **Status na tela**: conectado/desconectado/reconectando (com contagem), destino, bitrate, bateria e temperatura.
- **Proteção térmica**: notificação de alerta quando a bateria passa do limite (histerese de 2 °C).
- Senha cifrada com **AES-256-GCM no Android Keystore**; sem backup; credenciais nunca na URL nem em logs.
- `media-server/dev/`: MediaMTX 1.21.3 mínimo com usuários separados de publicação/leitura via `.env`.
- 14 testes unitários (JVM) + lint sem erros + build de release com R8.

## Estrutura

```
android-camera/
├── settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml   # versões centralizadas
└── app/src/main/java/dev/oldcam/camera/
    ├── OldCamApp.kt            # cria canais de notificação
    ├── Notifications.kt        # notificação fixa, alerta de temperatura, "toque para iniciar"
    ├── settings/               # CameraSettings, SettingsRepository, SecretCipher (Keystore)
    ├── stream/                 # StreamService, StreamEndpoint, ReconnectPolicy, StreamStatus
    ├── monitor/                # DeviceMonitor (bateria), TemperatureGuard (histerese)
    ├── boot/BootReceiver.kt
    └── ui/                     # MainActivity, SettingsActivity
```

## Como compilar e instalar

1. Abra `android-camera/` no **Android Studio** (versão com suporte a AGP 9.5) — ele baixa SDK e dependências.
   Pela linha de comando: `cd android-camera && ./gradlew assembleDebug` (JDK 17+ e `ANDROID_HOME`).
2. No celular: *Configurações → Sobre → toque 7× em "Número da versão"* → *Opções do desenvolvedor →
   Depuração USB*.
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Como testar (critério de aceite: stream no VLC)

### 1. Suba o servidor de teste no PC da mesma rede

```bash
cd media-server/dev
cp .env.example .env        # edite e troque as senhas (openssl rand -base64 24)
docker compose up
```

### 2. Configure o app

- URL do servidor: `rtsp://<IP-do-PC>:8554` (na LAN; o app vai avisar "sem criptografia" — esperado).
- Nome da câmera: `Garagem` → path `garagem`.
- Usuário/senha: `MTX_PUBLISH_USER` / `MTX_PUBLISH_PASS` do `.env`.
- Toque **Iniciar câmera**, aceite as permissões. O status deve ir para **Conectado** com bitrate > 0.

### 3. Abra no VLC

*Mídia → Abrir fluxo de rede* → `rtsp://<IP-do-PC>:8554/garagem` → o VLC pede usuário/senha:
use `MTX_READ_USER` / `MTX_READ_PASS`. Alternativa: `ffplay -rtsp_transport tcp rtsp://user:senha@<IP>:8554/garagem`.

### 4. Testes de robustez

| Teste | Como | Esperado |
|---|---|---|
| Autenticação | Publicar com senha errada | Status "Usuário/senha recusados", nova tentativa em 60 s |
| Leitura sem senha | VLC sem credenciais | 401, não abre |
| Leitor não publica | Usar o usuário de leitura no app | Recusado |
| Queda do servidor | `docker compose stop`, esperar, `start` | "Reconectando em Ns" com espera crescendo; volta sozinho |
| Queda do Wi-Fi | Desligar/ligar o Wi-Fi do celular | Reconecta logo que a rede volta |
| Tela apagada | Apagar a tela por 30+ min | VLC continua recebendo |
| Doze | `adb shell dumpsys deviceidle force-idle` | Continua (com otimização de bateria desativada) |
| Temperatura | `adb shell dumpsys battery set temp 470` (47 °C) | Notificação de alerta; `adb shell dumpsys battery reset` |
| Boot | Ativar "Iniciar automaticamente", reiniciar o celular | Android 8/9: volta sozinho. 10+: volta sozinho se "Sobrepor a outros apps" foi concedido; senão notificação "Toque para iniciar" |

### 5. No VPS (RTSPS)

A configuração de produção com certificado Let's Encrypt chega na Fase 2. Aí a URL será
`rtsps://cam.seudominio.com:8322`. O app exige certificado válido para o domínio (verificação de
hostname ligada), então certificado autoassinado **não** funciona, de propósito.

## Dicas para o celular-câmera

- Na tela inicial do app, toque **Desativar otimização de bateria**. Em Xiaomi/Samsung/Motorola, também
  libere "Execução em segundo plano"/"Sem restrições" nas configurações do app (veja dontkillmyapp.com).
- Deixe na tomada, **sem capinha**, longe do sol. Se der, limite a carga em 80% (opção de "proteger bateria"
  de muitos aparelhos): bateria 100% + calor 24/7 incha.
- 720p a 15 fps ≈ 2 Mbps ≈ 21 GB/dia de upload por câmera. 480p ≈ 10 GB/dia.

## Pendências (para fases seguintes ou melhorias)

- **Prévia da câmera na tela** para ajudar a posicionar (hoje: confira no VLC).
- **Áudio** opcional (desligado de propósito).
- Bitrate configurável manualmente e H.265 opcional (economiza banda em aparelhos que suportam).
- Credencial de publicação **por câmera** (definir junto com o CRUD da Fase 3).
- Testes instrumentados (em aparelho/emulador); aqui só rodaram os testes JVM — não havia emulador
  no ambiente de build, então o fluxo câmera → MediaMTX ainda **precisa ser validado num celular real**.
- APK de release assinado (keystore fica fora do repo).
- Ícones raster para launchers que não suportam ícone adaptativo (todos com Android 8+ suportam).

## Riscos conhecidos

- **Fabricantes agressivos** (MIUI, One UI, EMUI) podem matar o serviço mesmo assim. Mitigação: exceção de
  bateria + ajustes do fabricante. Se o processo morrer, o `START_STICKY` recria o serviço, mas no Android 11+
  ele pode não ter acesso à câmera em segundo plano: aparece a notificação "Toque para iniciar".
- **Boot no Android 10+** depende da permissão "Sobrepor a outros apps"; sem ela é preciso tocar na notificação.
- **Encoders de celulares antigos** variam: se 720p falhar ("não suporta a resolução"), use 480p ou menos FPS.
- **Temperatura**: só alertamos (não reduzimos a qualidade automaticamente). Uso 24/7 com calor degrada a bateria.
- A temperatura lida é a da **bateria** (a única disponível em todos os aparelhos); a do SoC pode ser maior.
- O `rtsp://` sem TLS fica disponível para a LAN; na internet use sempre `rtsps://`.
- O RootEncoder traz BouncyCastle, que o lint aponta por ter `TrustManager` vazio dentro da lib. O OldCam não
  usa esse caminho: o socket Java usa o TrustManager padrão do sistema com verificação de hostname.
- `targetSdk` 36 (não 37): subir depois de revisar as mudanças de comportamento do Android 17.
