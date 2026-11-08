# media-server/

- `dev/` — MediaMTX mínimo para validar a **Fase 1** (RTSP na LAN, sem gravação, sem TLS).
  ```bash
  cd dev && cp .env.example .env   # troque as senhas!
  docker compose up
  ```
- Configuração de produção (RTSPS com Let's Encrypt, gravação fMP4 com retenção, WebRTC, HLS/playback,
  API restrita, script de espaço em disco) chega na **Fase 2**.
