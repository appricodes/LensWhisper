# LensWhisper Desktop Companion (Phase 1)

A standalone desktop program that analyzes images locally using a small vision-language GGUF
model run via [llama.cpp](https://github.com/ggml-org/llama.cpp)'s `llama-server`. Runs CPU-only
by default (no GPU required), tested with SmolVLM2-500M and LFM2-VL-450M. Optional GPU
acceleration via `--gpu` — see below.

This is **Phase 1**: no phone involved. It's a CLI that analyzes one image at a time, plus an
HTTP server that speaks the `/process.php` JSON contract the Android app's Local Server engine
(`network/LocalServerEngine.kt`) expects, so a phone on the same network can point at it by just
changing a base URL. The user-facing setup guide is `local_server_setup.html` in this folder.

This is a **separate Gradle project** from the Android app (`desktop/` has its own
`settings.gradle.kts` and Gradle wrapper) — Android Studio won't sync it. Open `desktop/` in
IntelliJ IDEA, or just use the terminal.

## Graphical control panel

Run the app with **no arguments** and it opens a control panel in your default browser:

```bash
cd desktop
./gradlew run
```

(or, on a machine with no JDK installed, run the standalone `LensWhisper Desktop.exe` built by
`jpackage` instead — see "Building a standalone distribution" below.) From the page you can: pick
a model and download it if it isn't
present yet (with a live progress bar), choose CPU or GPU, start/stop the local server, and watch
live stats (requests, generation time, tokens generated, tokens/sec) update every second. If you
pick GPU and it can't actually start (e.g. no compatible driver), the page shows a clear error —
it does **not** silently fall back to CPU the way the CLI's `--gpu` flag does (see below).

The page is plain HTML/CSS/JS served locally (`src/main/resources/static/`) and opened in a
browser rather than a native window, specifically so it works with screen readers (NVDA/JAWS/
Narrator, VoiceOver, Orca) on all three OSes — see the comment on `launchGui()` in `Main.kt` for
why. It binds `0.0.0.0`, same as `serve` below, so it's reachable from other devices on the
network — `/process.php` requires a password (see below), every other route doesn't.

It opens in an **app-mode window** (no tabs, no address bar, its own taskbar/dock entry) via
Edge or Chrome's `--app=<url>` flag if either is installed — not a normal tab in whatever your
system's default browser happens to be (Firefox has no equivalent flag, so it always opens as a
regular tab there). Falls back to your default browser automatically if neither Edge nor Chrome
is found. See `BrowserLauncher.kt`.

## CLI

Requires a JDK 17+ on your PATH. First, download llama.cpp and a model (~650 MB total for the
default model):

```bash
cd desktop
./gradlew run --args="setup --model smolvlm2-500m"
```

Available model ids: `smolvlm2-500m` (default, fastest), `lfm2-vl-450m`, `smolvlm2-2200m`,
`qwen-vl-2b`, and `gemma4-e2b` (largest, highest quality) — see `model_catalog.json` for exact
files/sizes. Files land in a per-OS data directory:

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

## GPU acceleration (optional)

Add `--gpu` to `setup`, `analyze`, or `serve`:

```bash
./gradlew run --args="setup --model smolvlm2-500m --gpu"      # once, to fetch the GPU build
./gradlew run --args="analyze --image test_images/sample.jpg --gpu"
./gradlew run --args="serve --port 8765 --gpu"
```

- **Windows/Linux**: `setup --gpu` downloads a separate Vulkan build (works with any modern
  NVIDIA/AMD/Intel GPU — just needs a normal graphics driver, no CUDA/ROCm toolkit). `analyze`/
  `serve --gpu` try it first and **automatically fall back to CPU** if it fails to start (e.g. no
  compatible GPU or driver) — same behavior as the Android app's own GPU→CPU fallback.
- **macOS**: no extra download — the standard build already includes Metal GPU support, `--gpu`
  just turns it on.
- `serve`'s startup line and `GET /health` (`backend` field) report which backend actually ended
  up running, e.g. `gpu (vulkan)`, `gpu (metal)`, `cpu`, or `cpu (gpu fallback)`.

Whether GPU is actually faster depends heavily on the model size and GPU: for the small default
model (SmolVLM2-500M) on an entry-level/older GPU, CPU can be just as fast or faster since the
model barely exceeds cache sizes and per-request overhead (process/model load) dominates: try
both and compare. The gap tends to favor GPU more with larger models.

## Building a standalone distribution

```bash
./gradlew jpackageImage
```

Produces a native app image at `build/jpackage/LensWhisper Desktop/` — on Windows, that's
`LensWhisper Desktop.exe` plus a bundled JRE (`runtime/`) and every dependency jar (`app/`), so
end users can double-click it directly with **no separate Java install**. Requires a JDK 17+ on
the build machine (jpackage ships with the JDK itself); the resulting app image only needs the
target OS, not a JDK.

This is `--type app-image` (a runnable folder), not a proper installer (`--type exe`/`msi`) —
those need the [WiX Toolset](https://wixtoolset.org/) on the build machine, which isn't set up
here yet. Once WiX is installed, add `--type exe` (and an `--icon`) to the `jpackageImage` task in
`build.gradle.kts` to produce a real `setup.exe`; everything else stays the same. To hand someone
the app image today, zip the `LensWhisper Desktop` folder and send it — it runs as-is once
unzipped, no installation step.

## Later phases (not implemented yet)

- **Phone on the same LAN**: the app gets a "Desktop server" base-URL setting; discovery via
  mDNS. `serve` already binds `0.0.0.0` so it's reachable from other devices on the network.
- **Relay server**: for phones not on the same network, a small cloud relay pairs phone and
  desktop and proxies requests over an outbound WebSocket from the desktop app.
- **A real installer** (`--type exe`/`msi`/`dmg`/`deb`/`rpm`) once WiX (Windows) is set up on the
  build machine — see "Building a standalone distribution" above; the app-image build already
  works today.

## Notes

- llama.cpp release tag and GGUF model URLs are pinned in `AppConfig.kt` / `ModelCatalog.kt`.
  Bump the tag by hand to upgrade — see the comment in `AppConfig.kt`.
- Prompt wording is a straight port of the Android app's `OfflinePromptBuilder.kt`, which itself
  mirrors `backend/process.php`. Keep all three in sync if wording changes.
