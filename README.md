# CubicAI

A personal Android AI chat app (Kotlin + Jetpack Compose) with an optional private gateway that runs
your own Ollama models on your PC, reachable from the phone over home Wi-Fi or, if you allow it, Tailscale.

- Chats with photos, camera, files and PDFs; web search; per-chat behaviour and model.
- Providers: Local PC (Ollama), Google Gemini (chat, photos, image creation with billing), xAI, OpenRouter, any OpenAI-compatible endpoint.
- PC gateway (`server/`): TLS 1.3 with a pinned certificate, bearer token, home-network-only by default,
  private web search through a local SearXNG. Message content is never logged.

Build: `./gradlew assembleRelease`, then `adb install -r app/build/outputs/apk/release/app-release.apk`.
Server: see `server/cubicai_server.py --help` and the "CubicAI Server" control panel (`server/cubicai_control.py`).

The Chakra Petch font is licensed under the SIL Open Font License (`licenses/ChakraPetch-OFL.txt`).
