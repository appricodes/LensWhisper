"""PP-OCRv5 mobile det ONNX -> torch (onnx2torch) -> LiteRT with one signature per fixed input size
(weights shared), validated against ONNX Runtime. Usage: o2t_det.py <onnx> <out.tflite>
Signature names are d<H>x<W>; input is NCHW float32 [1,3,H,W], output the [1,1,H,W] text probability map."""
import sys, os, time, numpy as np, torch, onnx, onnxruntime as ort
from onnx import version_converter, helper
from onnx2torch import convert
import onnx2torch.node_converters.batch_norm as _bn
import litert_torch

_bn._BN_CLASS_FROM_SPATIAL_RANK[-2] = torch.nn.BatchNorm2d

from onnx2torch.node_converters.registry import _CONVERTER_REGISTRY
from onnx2torch.utils.common import OperationConverterResult, onnx_mapping_from_node


class _NearestUpsample(torch.nn.Module):
    # The det neck's Resize ops are nearest/asymmetric/floor with constant integer scales, which is
    # exactly F.interpolate(mode="nearest"); onnx2torch's generic Resize reads the scales tensor at
    # run time, which torch.export cannot trace.
    def __init__(self, scale):
        super().__init__()
        self.scale = scale

    def forward(self, x):
        return torch.nn.functional.interpolate(x, scale_factor=self.scale, mode='nearest')


def _resize(node, graph):
    a = node.attributes
    assert a.get('mode') == 'nearest' and a.get('coordinate_transformation_mode') == 'asymmetric' and a.get('nearest_mode') == 'floor'
    scales = graph.initializers[node.input_values[2]].to_torch().tolist()
    assert scales[:2] == [1.0, 1.0] and all(float(v).is_integer() for v in scales[2:]), scales
    return OperationConverterResult(
        torch_module=_NearestUpsample((int(scales[2]), int(scales[3]))),
        onnx_mapping=onnx_mapping_from_node(node).__class__(inputs=(node.input_values[0],), outputs=node.output_values))


for k in [k for k in _CONVERTER_REGISTRY if k.operation_type == 'Resize']:
    _CONVERTER_REGISTRY[k] = _resize
SIZES = [(960, 960), (960, 736), (736, 960), (960, 544), (544, 960)]

src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
names = {i.name for i in m.graph.input}
extra = [helper.make_tensor_value_info(i.name, i.data_type, list(i.dims)) for i in m.graph.initializer if i.name not in names]
m.graph.input.extend(extra)
m = version_converter.convert_version(m, 13)
keep = [i for i in m.graph.input if i.name not in {e.name for e in extra}]
del m.graph.input[:]
m.graph.input.extend(keep)
m.ir_version = max(m.ir_version, 8)
for d, v in zip(m.graph.input[0].type.tensor_type.shape.dim, (1, 3, 960, 960)):
    d.Clear()
    d.dim_value = v
del m.graph.value_info[:]
m = onnx.shape_inference.infer_shapes(m, data_prop=True)
tmp = dst + '.tmp.onnx'
onnx.save(m, tmp)
tm = convert(tmp).eval()
os.remove(tmp)

orig = ort.InferenceSession(src)
for h, w in SIZES[:2]:
    x = np.random.RandomState(h + w).rand(1, 3, h, w).astype(np.float32) * 2 - 1
    with torch.no_grad():
        t = tm(torch.from_numpy(x)).numpy()
    print(f'torch {h}x{w} maxdiff', float(np.abs(t - orig.run(None, {'x': x})[0]).max()))

builder = None
for h, w in SIZES:
    args = (torch.zeros(1, 3, h, w),)
    builder = litert_torch.signature(f'd{h}x{w}', tm, args) if builder is None else builder.signature(f'd{h}x{w}', tm, args)
builder.convert().export(dst)

from ai_edge_litert.interpreter import Interpreter
it = Interpreter(dst, num_threads=4)
sigs = it.get_signature_list()
for h, w in SIZES:
    name = f'd{h}x{w}'
    x = np.random.RandomState(h * w).rand(1, 3, h, w).astype(np.float32) * 2 - 1
    t0 = time.time()
    out = list(it.get_signature_runner(name)(**{sigs[name]['inputs'][0]: x}).values())[0]
    ms = (time.time() - t0) * 1000
    r = orig.run(None, {'x': x})[0]
    print(f'tflite {name} out {out.shape} maxdiff {float(np.abs(out - r).max()):.2e} '
          f'bitmap-agree {float(((out > 0.3) == (r > 0.3)).mean()):.6f} ms {ms:.0f}')
print('size', os.path.getsize(dst))
