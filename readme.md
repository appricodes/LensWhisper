# LensWhisper

An Android app that describes the world to blind and low-vision users — entirely on the phone.
Point the camera (or open a photo or video) and LensWhisper speaks a description through TalkBack:
scenes, text, documents, objects and colours, barcodes, light level. The AI runs on-device; no
photo ever leaves the phone.

## Features

- **Describe** scenes, photos and video frames with an on-device vision-language model
  (Gemma 4, InternVL3.5 or Qwen2-VL via [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM),
  downloaded from Hugging Face on first use).
- **Read text and documents** with Google ML Kit (Latin, Chinese, Japanese, Korean, Devanagari)
  or bundled PaddleOCR PP-OCRv5 models on LiteRT (Persian, Arabic, Cyrillic).
- **Detect objects** with ML Kit Image Labeling; **identify objects and colours** and **explore a
  photo by touch** with a bundled YOLACT (ResNet-101) segmentation model.
- **Scan barcodes** with ML Kit.
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

Proprietary — all rights reserved. [NOTICE](NOTICE) lists the bundled third-party components:

- `app/src/main/assets/models/yolact_resnet101_550_int8.tflite` — YOLACT by
  [Daniel Bolya et al.](https://github.com/dbolya/yolact), MIT, TFLite conversion from
  [PINTO_model_zoo](https://github.com/PINTO0309/PINTO_model_zoo) (MIT).
- `app/src/main/assets/models/ocr/` — PP-OCRv5 mobile text detection and Arabic/Cyrillic
  recognition by [PaddlePaddle](https://github.com/PaddlePaddle/PaddleOCR), Apache-2.0, converted
  from the official ONNX releases to LiteRT (see NOTICE and `tools/ocr_models/`).
- Google ML Kit (Play Services, unbundled), under the ML Kit Terms of Service.

The downloadable vision-language models are not part of this repository and each comes under its
own licence (all currently Apache-2.0), shown in the app under Legal Information.
