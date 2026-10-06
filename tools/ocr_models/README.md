# OCR model conversion

Rebuilds `app/src/main/assets/models/ocr/` (PaddleOCR PP-OCRv5 mobile, Apache-2.0) from
PaddlePaddle's official ONNX releases. The app bundles only the detector and the `arabic` and
`cyrillic` recognizers — ML Kit reads every other script (see `offline/OfflineTextRecognizer.kt`);
the other recognizers can still be built the same way. Run on Linux/WSL with Python 3.10+:

```bash
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install onnx onnxruntime onnx2torch litert-torch ai-edge-litert ai-edge-quantizer pyyaml pillow
```

1. Download `inference.onnx` + `inference.yml` from `huggingface.co/PaddlePaddle/<model>_onnx` into
   `onnx/<model>/` for `PP-OCRv5_mobile_det`, `PP-OCRv5_mobile_rec` and the
   `latin_/arabic_/cyrillic_/devanagari_/korean_PP-OCRv5_mobile_rec` variants.
2. `python convert_det.py onnx/PP-OCRv5_mobile_det/inference.onnx det_fp32.tflite`
3. `python convert_rec.py onnx/<model>/inference.onnx rec_<name>_fp32.tflite` for each recognizer
   (`ch` = the plain `PP-OCRv5_mobile_rec`).
4. `python quant.py <in>_fp32.tflite <assets>/<out>.tflite weight_only_wi8_afp32` for each.
5. `python mkdict.py` writes the `dict_*.txt` files.

Both converters print their max difference against ONNX Runtime for every signature; anything
above ~1e-4 or an argmax/bitmap agreement below 1.0 means the conversion is wrong.

`ocr_ref.py` is a Python reimplementation of the app's pipeline (`offline/PaddleOcr.kt`) that
runs on either ONNX Runtime or the converted LiteRT models, for comparing them on real photos.
