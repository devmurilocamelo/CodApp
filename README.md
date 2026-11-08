# OldCam

Câmeras de segurança DIY com celulares Android antigos: gravação 24/7 e acesso remoto seguro.

```
[Celular Android] --RTSPS (TLS)--> [MediaMTX no VPS] <--(proxy autenticado)-- [Painel Laravel] <-- navegador/PWA
```

| Módulo | Estado |
|---|---|
| [`android-camera/`](android-camera/) — app câmera (Kotlin, CameraX, RootEncoder) | Fase 1 — pronto para teste |
| [`media-server/`](media-server/) — MediaMTX (Docker Compose) | `dev/` para teste; produção na Fase 2 |
| [`web/`](web/) — painel Laravel + Inertia + React | Fase 3 |
| [`docs/`](docs/) — arquitetura e guias | contínuo |

- Arquitetura: [docs/arquitetura.md](docs/arquitetura.md)
- Fase 1 (instalar e testar o app): [docs/fase-1-android.md](docs/fase-1-android.md)
- Convenções e decisões do projeto: [CLAUDE.md](CLAUDE.md)
