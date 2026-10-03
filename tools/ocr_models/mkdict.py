"""Writes app/src/main/assets/models/ocr/dict_<model>.txt from each model's inference.yml (run from this folder, with onnx/<model>/ downloaded)."""
import yaml, os
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets', 'models', 'ocr')
for model, d in [('latin', 'latin_PP-OCRv5_mobile_rec'), ('arabic', 'arabic_PP-OCRv5_mobile_rec'), ('cyrillic', 'cyrillic_PP-OCRv5_mobile_rec'),
                 ('devanagari', 'devanagari_PP-OCRv5_mobile_rec'), ('korean', 'korean_PP-OCRv5_mobile_rec'), ('ch', 'PP-OCRv5_mobile_rec')]:
    chars = [str(c) for c in yaml.safe_load(open(f'onnx/{d}/inference.yml', encoding='utf-8'))['PostProcess']['character_dict']]
    assert all('\n' not in c and len(c) >= 1 for c in chars), model
    with open(os.path.join(OUT, f'dict_{model}.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(chars) + '\n')
    multi = [c for c in chars if len(c) != 1]
    print(model, len(chars), 'multi-char entries:', len(multi), ascii(multi[:5]))
