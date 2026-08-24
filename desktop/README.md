# LensWhisper Desktop Companion (Phase 1)

A standalone desktop program that analyzes images locally using a small vision-language GGUF
model run via [llama.cpp](https://github.com/ggml-org/llama.cpp)'s `llama-server`. No GPU
required — CPU inference only, tested with SmolVLM2-500M and LFM2-VL-450M.

This is **Phase 1**: no phone involved. It's a CLI that analyzes one image at a time, plus an
HTTP server that speaks the exact same JSON contract as the Android app's backend
(`backend/process.php` in the repo root), so a phone on the same network can later point at it
by just changing a base URL.

This is a **separate Gradle project** from the Android app (`desktop/` has its own
`settings.gradle.kts` and Gradle wrapper) — Android Studio won't sync it. Open `desktop/` in
IntelliJ IDEA, or just use the terminal.

## Setup

Requires a JDK 17+ on your PATH. First, download llama.cpp and a model (~650 MB total for the
default model):

```bash
cd desktop
./gradlew run --args="setup --model smolvlm2-500m"
```

Available model ids: `smolvlm2-500m` (default, fastest) and `lfm2-vl-450m`. Files land in a
per-OS data directory:

- Windows: `%LOCALAPPDATA%\LensWhisperDesktop`
- macOS: `~/Library/Application Support/LensWhisperDesktop`
- Linux: `~/.local/share/lenswhisper-desktop`

Override with `--data-dir <path>` on any command. If `setup` can't run (air-gapped machine,
corporate proxy), you can place files manually:
`<data-dir>/bin/<release-tag>/` for the extracted llama.cpp release,
`<data-dir>/models/` for the `.gguf` model + mmproj files (see `ModelCatalog.kt` for exact
filenames and download URLs).

## Analyze one image (CLI)

```bash
./gradlew run --args="analyze --image test_images/sample.jpg"
./gradlew run --args="analyze --image test_images/sample.jpg --prompt-id detailed_scene --stream"
./gradlew run --args="analyze --image test_images/sample.jpg --prompt-id custom --custom 'What color is the largest object?'"
```

Flags: `--prompt-id` (`brief_narration` default; also `detailed_scene`, `people_and_outfit`,
`emotions`, `objects`, `read_texts`, `story_5`, `story_10`, `custom`, `explore`), `--language`
(default `en-US`), `--persona` (default `Neutral`; see `HttpApi.kt` for the full allowed list),
`--custom "<question>"` (required with `--prompt-id custom`), `--stream`, `--model <id>`.

Drop your own test photo into `test_images/` and point `--image` at it.

## Run as a server

```bash
./gradlew run --args="serve --port 8765"
```

Then, from another terminal:

```bash
curl -s http://localhost:8765/health

B64=$(base64 -w0 test_images/sample.jpg)

# Non-streaming
curl -s -X POST http://localhost:8765/process.php -H "Content-Type: application/json" \
  -d "{\"install_id\":\"test\",\"prompt_id\":\"brief_narration\",\"language\":\"en-US\",\"persona\":\"Neutral\",\"frames\":[\"$B64\"]}"

# Streaming (NDJSON: one {"delta":...} line per fragment, then {"done":...})
curl -sN -X POST http://localhost:8765/process.php -H "Content-Type: application/json" \
  -d "{\"install_id\":\"test\",\"prompt_id\":\"detailed_scene\",\"language\":\"en-US\",\"persona\":\"Neutral\",\"streaming\":true,\"frames\":[\"$B64\"]}"
```

`frames` is a JSON array of raw base64-encoded JPEG bytes — same format the Android app sends.

## Later phases (not implemented yet)

- **Phone on the same LAN**: the app gets a "Desktop server" base-URL setting; discovery via
  mDNS. `serve` already binds `0.0.0.0` so it's reachable from other devices on the network.
- **Relay server**: for phones not on the same network, a small cloud relay pairs phone and
  desktop and proxies requests over an outbound WebSocket from the desktop app.
- **Packaging**: `jpackage` installers per OS.

## Notes

- llama.cpp release tag and GGUF model URLs are pinned in `AppConfig.kt` / `ModelCatalog.kt`.
  Bump the tag by hand to upgrade — see the comment in `AppConfig.kt`.
- Prompt wording is a straight port of the Android app's `OfflinePromptBuilder.kt`, which itself
  mirrors `backend/process.php`. Keep all three in sync if wording changes.
