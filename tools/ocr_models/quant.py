"""Quantizes a LiteRT model with ai-edge-quantizer. Usage: quant.py <in.tflite> <out.tflite> <recipe-name>"""
import sys, os
from ai_edge_quantizer import quantizer, recipe

src, dst, name = sys.argv[1:4]
q = quantizer.Quantizer(src)
q.load_quantization_recipe(getattr(recipe, name)())
res = q.quantize()
if os.path.exists(dst):
    os.remove(dst)
res.export_model(dst)
print(name, os.path.getsize(src), '->', os.path.getsize(dst))
