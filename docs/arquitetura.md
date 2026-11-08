# Arquitetura

```mermaid
flowchart LR
    subgraph Casa["Casa / locais monitorados"]
        A1["Celular antigo 1<br/>OldCam (Android)"]
        A2["Celular antigo N"]
    end

    subgraph VPS["VPS Linux (domínio próprio)"]
        M["MediaMTX<br/>RTSPS :8322 (publicar)<br/>gravação fMP4 24/7"]
        D[("Disco<br/>recordings/")]
        L["Laravel (painel)<br/>auth + 2FA + proxy"]
        R["Caddy/Nginx<br/>TLS automático, rate limit"]
    end

    U["Navegador / PWA"]

    A1 -- "RTSPS + usuário de PUBLICAÇÃO" --> M
    A2 -- "RTSPS" --> M
    M --> D
    L -- "API/WHEP/playback<br/>com usuário de LEITURA (só no servidor)" --> M
    U -- "HTTPS (sessão Laravel)" --> R --> L
```

## Fluxo de credenciais

| Credencial | Onde vive | Quem usa |
|---|---|---|
| Publicação (por câmera ou compartilhada) | `.env` do servidor + cifrada no Keystore do celular | só os celulares |
| Leitura | `.env` do servidor | só o Laravel (backend) |
| Login do painel (+2FA) | banco do Laravel (hash) | pessoas |

O navegador fala **apenas** com o Laravel. Ao vivo (WebRTC/WHEP) e gravações passam pelo Laravel
(proxy ou tokens curtos assinados), nunca com a senha do MediaMTX no front-end.

## Fase 1 — o app câmera por dentro

```mermaid
flowchart TB
    UI["MainActivity / SettingsActivity"] -- "start/stop/restart (Intent)" --> S
    S["StreamService<br/>(Foreground Service, tipo camera)"]
    S --> C["CameraXSource<br/>(CameraX)"]
    C --> E["Encoder H.264 por hardware<br/>(RootEncoder RtspStream)"]
    E -- "RTSP/RTSPS TCP" --> MTX["MediaMTX"]
    S -- "falhou?" --> RP["ReconnectPolicy<br/>backoff 1s→60s + jitter"]
    RP --> S
    S --> W["WakeLock + WifiLock"]
    DM["DeviceMonitor<br/>bateria/temperatura"] --> S
    S -- "StateFlow" --> ST["StreamStatus"] --> UI
    B["BootReceiver"] -- "Android 8/9: direto<br/>10+: via Activity ou notificação" --> S
```
