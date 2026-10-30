# CubicAI: personal Android AI chat app

Native Kotlin + Jetpack Compose, single `:app` module, package `dev.martin.cubicai`. Personal sideload only (no login, release signed with the debug key).

## Build / install
- `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (copy to `CubicAI.apk`)
- `adb install -r app/build/outputs/apk/release/app-release.apk`
- Toolchain: AGP 9.4.1 (built-in Kotlin), Kotlin compose plugin 2.4.21, Gradle 9.8.2, compileSdk 37, minSdk 29, JDK 27. SDK at ~/Android/Sdk.

## Layout
- `data/Settings.kt`: provider presets (Local PC, xAI, OpenRouter, Custom), SharedPreferences store. Local PC has a home URL and an optional Tailscale URL (`baseUrls`, tried in order).
- `data/Personas.kt`: built-in behaviours ("None" sends no system prompt). Custom ones live in settings; "About you" is appended to every behaviour except None.
- `data/SecretBox.kt`: API keys and the PC token are AES-GCM encrypted with an Android Keystore key.
- `data/Models.kt`: Chat/Message/Attachment + org.json (de)serialisation. No kotlinx.serialization on purpose. Each chat stores its own persona and model.
- `data/ChatStore.kt`: one JSON file per chat in filesDir/chats, attachments in filesDir/attachments.
- `data/Attachments.kt`: images → downscaled JPEG; PDFs → page images via PdfRenderer; other files → text.
- `net/LlmClient.kt`: OkHttp SSE streaming. `/chat/completions` normally; xAI + Search uses `/responses` with `web_search` + `x_search`. OpenRouter search uses `plugins:[{id:"web"}]`. Falls back across base URLs only on connect failure.
- `ChatViewModel.kt`: all state (`UiState`), streaming, edit/regenerate, personas, TTS. The whole history is resent each turn; photos only from the last 6 photo messages.
- `ui/`: Theme (cobalt + amber, Chakra Petch headings, cut-corner shapes), Cube (brand mark + thinking animation), ChatScreen, Messages, Behaviour sheet, Markdown (Markwon), ModelPicker, SettingsScreen.

## Design rules
- Shapes come from `MaterialTheme.shapes` (all `CutCornerShape`); buttons pass `shape = MaterialTheme.shapes.small`. No rounded/circle shapes.
- Headings use Chakra Petch via the typography; body stays Roboto.
- The cube is the only animated element (it rolls its light while a reply is generated).

## Local PC mode (server/)
- `server/cubicai_server.py`: stdlib-only HTTPS gateway: phone → (TLS 1.3, pinned self-signed cert, bearer token) → Ollama on 127.0.0.1:11434. Converts OpenAI chat format ↔ Ollama `/api/chat` (thinking → `delta.reasoning`, `cubic_status` progress events, `citations`).
- Only loopback + private (home) addresses may connect; Tailscale (100.64/10) only while `remote` is on (`cubicai_server.py remote on|off`, or the panel). Others are dropped before TLS.
- Context: a Modelfile `num_ctx` wins; otherwise settings.json `num_ctx` (default 16384), capped at the model's max. Reported as `context_length` in /v1/models. `keep_alive` 15m.
- Web search runs on the PC via SearXNG in Docker (`cubicai-searxng`, 127.0.0.1:8888, config in ~/.config/cubicai-server/searxng). Request flag `cubic_web_search: true`. Page fetches are limited to public addresses (SSRF guard, incl. redirects).
- Identity (cert, key, token, settings.json) in ~/.config/cubicai-server (0700). Pairing link `cubicai://pair?u=&r=&t=&f=`; `rotate` revokes phones.
- `server/cubicai_control.py`: GTK4/libadwaita panel (status, on/off, pair QR, remote access, context length, activity). Launcher "CubicAI Server".
- Venv at server/.venv (only for `qrcode`). Service: server/cubicai-server.service (systemd --user, sandboxed).
- Emulator reaches the PC at https://10.0.2.2:8443 (`pair --url https://10.0.2.2:8443`).
- Never log message content in the server.

## Notes
- All providers are treated as OpenAI-compatible; adding one = new entry in `Providers`.
- Messages list uses `reverseLayout` to stay pinned to the bottom while streaming.
