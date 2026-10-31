# CubicAI: personal Android AI chat app

Native Kotlin + Jetpack Compose, single `:app` module, package `dev.martin.cubicai`. Personal sideload only (no login, release signed with the debug key).

## Build / install
- `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (copy to `CubicAI.apk`)
- `adb install -r app/build/outputs/apk/release/app-release.apk`
- Toolchain: AGP 9.4.1 (built-in Kotlin), Kotlin compose plugin 2.4.21, Gradle 9.8.2, compileSdk 37, minSdk 29, JDK 27. SDK at ~/Android/Sdk.

## Layout
- `data/Settings.kt`: provider presets (Local PC, Gemini, xAI, OpenRouter, Custom), SharedPreferences store. Local PC has a home URL and an optional Tailscale URL (`baseUrls`, tried in order).
- `data/Personas.kt`: built-in behaviours ("None" sends no system prompt). Custom ones live in settings; "About you" is appended to every behaviour except None.
- `data/SecretBox.kt`: API keys and the PC token are AES-GCM encrypted with an Android Keystore key.
- `data/Models.kt`: Chat/Message/Attachment + org.json (de)serialisation. No kotlinx.serialization on purpose. Each chat stores its own persona and model.
- `data/ChatStore.kt`: one JSON file per chat in filesDir/chats, attachments in filesDir/attachments.
- `data/Attachments.kt`: images → downscaled JPEG; PDFs → page images via PdfRenderer; other files → text.
- `net/LlmClient.kt`: OkHttp SSE streaming. `/chat/completions` normally; xAI + Search uses `/responses` with `web_search` + `x_search`. OpenRouter search uses `plugins:[{id:"web"}]`. Falls back across base URLs only on connect failure (a TLS refusal wins over later errors so the "turned away" hint shows). A stream that ends without `[DONE]`/`finish_reason` is an error ("connection dropped"), not a silent cut. Don't shorten the LAN connect timeout: home Wi-Fi with 1 s RTT spikes then fails spuriously.
- `ChatViewModel.kt`: all state (`UiState`), streaming, edit/regenerate, personas, TTS. The whole history is resent each turn; photos only from the last 6 photo messages.
- `ui/`: Theme (cobalt + amber, Chakra Petch headings, cut-corner shapes), Cube (brand mark + thinking animation), ChatScreen, Messages, Behaviour sheet, Markdown (Markwon; streaming word fade, square bullets), ModelPicker, ContextMeter, SettingsScreen.
- Token usage: requests ask for `stream_options.include_usage`; the reply stores `promptTokens`/`outputTokens`. ContextMeter (beside send) shows last prompt+output against the model's `contextTokens`, estimating ~4 chars/token (marked `~`) when a provider doesn't report counts.

## Gemini
- Chat goes through Google's OpenAI-compatible endpoint (`/v1beta/openai`); default model `gemini-3.5-flash` (3.8 is often overloaded; 2.x models are closed to new keys and hidden from the picker).
- Image creation ("Image" chip, `LlmClient.generateImage`) uses the native `generateContent` API with `responseModalities: IMAGE`. Google's free tier has zero image quota, so it only works with billing enabled; the 429 is turned into a plain-language message.
- No web search for Gemini yet (the compat endpoint lacks grounding).

## Design rules
- Shapes come from `MaterialTheme.shapes` (all `CutCornerShape`); buttons pass `shape = MaterialTheme.shapes.small`. No rounded/circle shapes.
- Headings use Chakra Petch via the typography; body stays Roboto.
- One edge per surface: 16dp page gutter (messages, home, composer box, settings), 20dp inside bottom sheets. Icon buttons that start a row are pulled in by their 12-15dp inner padding so the icon, not the touch target, sits on the edge. Check with `uiautomator dump` bounds, not by eye.
- Menus use `CubicMenu` (raised tone + hairline edge). Selected rows in sheets get a tinted band. Destructive actions (Delete, Unpair) are `colorScheme.error`, including dialog confirms.
- Settings sections are `SettingsCard`s with `SettingRow`; toggles use `CutSwitch` (Material's Switch is pill-shaped). Theme is System/Light/Dark (`Settings.theme`, `isDark()`), and the system bar icons follow it.
- Motion is short (120-260 ms) and tied to state: the cube rolls its light, the phase label shimmers (Thinking, Searching, Writing), streamed words fade in one after another (`StreamFader`, 320 ms per word, alpha only so lines aren't re-measured) while the reply's height eases open, reasoning opens itself while the model thinks, list items fade in, the send/mic/stop button cross-fades.
- Never put a secret (API key, token) in the repo; keys are entered in Settings and stored encrypted by `SecretBox`.

## Local PC mode (server/)
- `server/cubicai_server.py`: stdlib-only HTTPS gateway: phone → (TLS 1.3, pinned self-signed cert, bearer token) → Ollama on 127.0.0.1:11434. Converts OpenAI chat format ↔ Ollama `/api/chat` (thinking → `delta.reasoning`, `cubic_status` progress events, `citations`, final `usage` from `prompt_eval_count`/`eval_count`).
- Listens dual-stack (`--bind ::`; IPv4 peers unwrapped from ::ffff:). Only loopback + private (home) addresses, plus the PC's own global IPv6 prefixes (`lan6()`, from /proc/net/if_inet6, skipping tailscale/docker/veth), may connect; Tailscale (100.64/10) only while `remote` is on (`cubicai_server.py remote on|off`, or the panel). Others are dropped before TLS, and the check repeats per request (403) so kept-alive Tailscale connections stop when remote goes off.
- /v1/models includes `cubic_remote` (the Tailscale URL while remote is on); the app adds/updates its stored remote URL from it, never clears it, so no re-pair is needed.
- Connection slots: 64 total, 12 per address. Error responses drain the unread body and close the connection.
- Context: a Modelfile `num_ctx` wins; otherwise settings.json `num_ctx` (default 16384), capped at the model's max. Reported as `context_length` in /v1/models. `keep_alive` 15m.
- Web search runs on the PC via SearXNG in Docker (`cubicai-searxng`, 127.0.0.1:8888, config in ~/.config/cubicai-server/searxng). Request flag `cubic_web_search: true`. Page fetches are limited to public addresses (SSRF guard, incl. redirects).
- mDNS: the server runs `avahi-publish-service _cubicai._tcp 8443 fp=<cert sha256>`. When every address fails (not a TLS refusal, at most once per 30 s) `LlmClient` asks `net/LanFinder.kt` (NsdManager) for a service with the paired `fp`, takes its site-local IPv4 address and stores it as the new home URL. A media job that lost the PC retries as soon as the phone's network comes back (ConnectivityManager callback).
- IPv6 fallback: /v1/models also reports `cubic_lan6` (the PC's IPv6 home URL); the app stores it as `localLan6Url` (never cleared by the PC) and tries home IPv4 → IPv6 → Tailscale. The home router here intermittently stops bridging IPv4 between Wi-Fi clients after a reconnect (heals in ~13 min) while IPv6 works.
- Media jobs: following and each file download go through `reconnecting` (MEDIA_RECONNECTS, growing waits cut short by the network-back callback). Retry on a reply that still has `media.jobId` collects that job instead of making it again; a job that failed, was stopped or is gone (404) has its jobId cleared so Retry makes it anew. Deleting a chat cancels every job of its replies that has no files yet (running or undelivered). The janitor empties our ComfyUI's history whenever nothing is running (a cancelled run can be recorded after we cleared it).
- `Links` (ConnectivityManager): addresses the phone can't reach now are skipped instantly (100.64/10 and fd7a:115c:a1e0::/48 need a VPN; private and IPv6 home addresses need Wi-Fi/Ethernet or a VPN). The error then says why ("not on home Wi-Fi and Tailscale is off"; with Tailscale on and off Wi-Fi, that it can take a minute to reconnect after leaving Wi-Fi); a "connection refused" says the server isn't running.
- On start the media manager clears the queue and interrupts its own ComfyUI unit, so a job from a previous gateway never saves a file nobody will collect.
- Identity (cert, key, token, settings.json) in ~/.config/cubicai-server (0700). Pairing link `cubicai://pair?u=&r=&t=&f=`; `rotate` revokes phones.
- `server/cubicai_control.py`: GTK4/libadwaita panel (status, on/off, pair QR, remote access, context length, activity). Launcher "CubicAI Server".
- Venv at server/.venv (only for `qrcode`). Service: server/cubicai-server.service (systemd --user, sandboxed).
- Emulator reaches the PC at https://10.0.2.2:8443 (`pair --url https://10.0.2.2:8443`).
- Never log message content in the server.

## Create on the PC (ComfyUI)
- `server/cubicai_media.py`: `GENERATORS` (13: image/edit/tool/video/music/speech) map a saved UI workflow + selectors (`#node.input`, `@subgraphInput`) to prompt/seed/size/seconds/image/audio; `fixed` overrides (Chatterbox `repetition_penalty` 1.2: the default 2.0 never stops). Only generators whose model files exist are listed. The job picks its seed up front (0..2^32-1, clamped per node) and reports it; the reply stores it as `MediaRequest.seed` (outside `params`, so Regenerate still varies). `steps`/`cfg` selectors exist only for Qwen 2.1, Illustrious and Wan 5B (distilled models break off CFG 1); `/v1/media/generators` gives the workflow's own steps/cfg/negative as defaults (`_defaults`), and the sheet's "More controls" sends only changed values.
- `server/comfy_workflow.py`: UI workflow → API prompt (subgraphs flattened, widget order from `/object_info`, seed-control and upload slots skipped, muted/bypassed/reroute/primitive handled).
- ComfyUI runs as `cubicai-comfyui.service` on 127.0.0.1:8188, started on demand, stopped after `CUBICAI_COMFY_IDLE` (600 s). A running unit is adopted after a server restart. Ollama is unloaded before a job; ComfyUI is `/free`d before the next chat.
- GPU sharing (8 GB card): a job unloads Ollama (`free_ollama`); a chat during a job gets a "busy" status and loads partly on CPU, so `free_for_chat` afterwards also unloads any model not fully in VRAM (else 15 tok/s instead of ~100 for 15 min). ComfyUI start does reset-failed + a second start, since right after a crash systemd still calls the dying unit active. A dropped websocket becomes "ComfyUI stopped while making this". On server start, queued/running work in our ComfyUI unit is cleared.
- One job at a time. Routes: `GET /v1/media/generators`, `POST /v1/media/jobs` (base64 image/audio), `GET …/jobs/<id>/events` (SSE, previews as base64), `GET …/files/<n>` (after the last file the job is forgotten), `DELETE …/jobs/<id>`, `POST /v1/media/save?kind=&name=`.
- Temp policy: results in ~/.cache/cubicai-server/media until downloaded (TTL 1 h); ComfyUI input/output `cubicai/` emptied per job and at start; `--disable-metadata`. Saved copies go to /mnt/storage/games/AI_local/CubicAI/{Images,Videos,Music}.
- App: `net/MediaClient.kt`, `ui/CreateSheet.kt` (Create chip, Local PC only), `ui/Media.kt` (progress card, video/audio players, share). A reply's `media` (generator, params, jobId) lets it resume after a drop or app restart, Stop cancels on the PC, deleting a chat cancels its jobs. Media replies reach chat models as a one-line note. A generator that needs a photo uses the latest picture in the chat when none is attached (chained edits). The sheet preselects the last choice (`Settings.lastCreate`); create mode itself is not restored on start. Progress weights nodes by cost (`_weight`), previews arrive from the first sampling step.

## Notes
- All providers are treated as OpenAI-compatible; adding one = new entry in `Providers`.
- Messages list uses `reverseLayout` to stay pinned to the bottom while streaming.
- Drawer search shows from 6 chats, matches titles and message text from the start of a word; it clears (and drops focus) when the drawer closes.
- README screenshots live in docs/screenshots (540 px wide, taken with the SystemUI demo-mode status bar, no personal content).
