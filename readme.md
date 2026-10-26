# LensWhisper

An Android app that describes the world to blind and low-vision users — entirely on the phone.
Point the camera (or open a photo or video) and LensWhisper speaks a description through TalkBack:
scenes, objects and colours, barcodes, light level. The AI runs on-device; no
photo ever leaves the phone.

## Features

- **Describe** scenes, photos and video frames with an on-device vision-language model
  (Gemma 4, InternVL3.5 or Qwen2-VL via [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM),
  downloaded from Hugging Face on first use).
- **Detect objects, identify objects and colours** and **explore a photo by touch** with a bundled
  YOLOv8m-seg segmentation model.
- **Scan barcodes** with zxing-cpp.
- Look products up in an optional offline product database (built from
  Open Food Facts / Open Products Facts), and attach voice or text notes to each barcode.
- Built for TalkBack throughout: every result is announced, every control is labelled.

## Building

Requirements: Android Studio (or JDK 17+ and the Android SDK), minSdk 26, compileSdk 36.

```bash
./gradlew :app:assembleDebug
```

A fresh clone builds as-is. Release builds additionally need:

- `secrets.properties` — copy `secrets.properties.example` and set `BACKUP_PASSPHRASE` (the key
  the Export/Import feature encrypts `.fdb` files with). The build refuses to make a release with
  the placeholder.
- Your own signing configuration.

## Repository layout

| Path | What |
|---|---|
| `app/` | The Android app |
| `desktop/` | Optional desktop companion (llama.cpp server); the app side is built but switched off — see `LocalServerFeature.kt` |
| `tools/barcode_db/` | Scripts that build and publish the offline product database |

## Licence

GNU Affero General Public License v3.0 or later — see [LICENSE](LICENSE). [NOTICE](NOTICE) lists
the bundled third-party components:

- `app/src/main/assets/models/yolov8m_seg_int8.tflite` — YOLOv8m-seg by
  [Ultralytics](https://github.com/ultralytics/ultralytics), AGPL-3.0, exported with Ultralytics'
  own TFLite exporter (int8).

The downloadable vision-language models are not part of this repository and each comes under its
own licence (all currently Apache-2.0), shown in the app under Legal Information.
