"""PP-OCRv5 rec ONNX (official export, re-param branches already fused) -> torch (onnx2torch) ->
LiteRT (litert-torch) with a DYNAMIC input width, validated against ONNX Runtime at several widths.
Usage: o2t_dyn.py <onnx> <out.tflite>"""
import sys, os, time, numpy as np, torch, onnx, onnxruntime as ort
from onnx import version_converter, numpy_helper, helper
from onnx.reference import ReferenceEvaluator
from onnx2torch import convert
import litert_torch
from torch.export import Dim

import onnx2torch.node_converters.batch_norm as _bn
import onnx2torch.node_converters.squeeze as _sq
import onnx2torch.node_converters.slice as _sl
from onnx2torch.node_converters.registry import _CONVERTER_REGISTRY, OperationDescription
from onnx2torch.utils.common import OperationConverterResult, onnx_mapping_from_node

H = 48
src, dst = sys.argv[1], sys.argv[2]

# ---------------------------------------------------------------- onnx2torch patches
# Every BatchNorm here is on a 4-D conv output; onnx2torch loses the rank of some of them.
_bn._BN_CLASS_FROM_SPATIAL_RANK[-2] = torch.nn.BatchNorm2d
import onnx2torch.node_converters.average_pool as _ap
_ap._AVGPOOL_CLASS_FROM_SPATIAL_RANK[-2] = torch.nn.AvgPool2d  # same story: 4-D input, rank lost


def _mapping_first_input(node):
    return onnx_mapping_from_node(node).__class__(inputs=(node.input_values[0],), outputs=node.output_values)


def _static_squeeze(node, graph):
    axes = graph.initializers[node.input_values[1]].to_torch().tolist() if len(node.input_values) > 1 else None
    return OperationConverterResult(torch_module=_sq.OnnxSqueezeStaticAxes(axes=axes), onnx_mapping=_mapping_first_input(node))


class _StaticSlice(torch.nn.Module):
    def __init__(self, starts, ends, axes, steps):
        super().__init__()
        self.f, self.p, self.n = _sl._get_slices(starts, ends, axes, steps)

    def forward(self, x):
        return _sl._do_slice(x, self.f, self.p, self.n)


def _static_slice(node, graph):
    vals = [graph.initializers[v].to_torch() for v in node.input_values[1:]]
    return OperationConverterResult(
        torch_module=_StaticSlice(vals[0].tolist(), vals[1].tolist(), vals[2] if len(vals) > 2 else None, vals[3] if len(vals) > 3 else None),
        onnx_mapping=_mapping_first_input(node))


class _StaticReshape(torch.nn.Module):
    def __init__(self, shape):
        super().__init__()
        self.shape = shape

    def forward(self, x):
        return torch.reshape(x, [x.shape[i] if d == 0 else d for i, d in enumerate(self.shape)])


def _static_reshape(node, graph):
    return OperationConverterResult(torch_module=_StaticReshape(graph.initializers[node.input_values[1]].to_torch().tolist()),
                                    onnx_mapping=_mapping_first_input(node))


for v in (13, 21):
    _CONVERTER_REGISTRY[OperationDescription('', 'Squeeze', v)] = _static_squeeze
for v in (10, 11, 13):
    _CONVERTER_REGISTRY[OperationDescription('', 'Slice', v)] = _static_slice
for v in (5, 13, 14):
    _CONVERTER_REGISTRY[OperationDescription('', 'Reshape', v)] = _static_reshape


class _MatMulBatch1(torch.nn.Module):
    # torch.matmul broadcasts via a symbolic expand that litert-torch cannot lower with a dynamic
    # width; with batch fixed at 1 every MatMul here is a plain mm/bmm.
    def forward(self, a, b):
        if a.dim() == 3 and b.dim() == 2:
            return torch.mm(a[0], b).unsqueeze(0)
        if a.dim() == 4 and b.dim() == 4:
            return torch.bmm(a[0], b[0]).unsqueeze(0)
        if a.dim() == 2 and b.dim() == 2:
            return torch.mm(a, b)
        return torch.matmul(a, b)


def _matmul(node, graph):
    return OperationConverterResult(torch_module=_MatMulBatch1(), onnx_mapping=onnx_mapping_from_node(node))


for k in [k for k in _CONVERTER_REGISTRY if k.operation_type == 'MatMul']:
    _CONVERTER_REGISTRY[k] = _matmul


# ---------------------------------------------------------------- ONNX preparation
def load_opset13(path):
    m = onnx.load(path)
    if m.opset_import[0].version < 13:
        names = {i.name for i in m.graph.input}
        extra = [helper.make_tensor_value_info(i.name, i.data_type, list(i.dims)) for i in m.graph.initializer if i.name not in names]
        m.graph.input.extend(extra)
        m = version_converter.convert_version(m, 13)
        keep = [i for i in m.graph.input if i.name not in {e.name for e in extra}]
        del m.graph.input[:]
        m.graph.input.extend(keep)
    # Paddle's 0-means-copy reshape targets: the batch is always 1 here.
    init = {i.name: i for i in m.graph.initializer}
    for n in m.graph.node:
        if n.op_type == 'Reshape' and n.input[1] in init:
            v = numpy_helper.to_array(init[n.input[1]]).copy()
            if v.size and v[0] == 0:
                v[0] = 1
                init[n.input[1]].CopyFrom(numpy_helper.from_array(v, n.input[1]))
    return m


def collapse_flatten_softmax(m):
    """Flatten(axis=r-1) -> Softmax(-1) -> Reshape(back) is just Softmax over the last axis."""
    prod = {o: n for n in m.graph.node for o in n.output}
    cons = {}
    for n in m.graph.node:
        for i in n.input:
            cons.setdefault(i, []).append(n)
    drop, count = set(), 0
    for sm in m.graph.node:
        if sm.op_type != 'Softmax':
            continue
        fl = prod.get(sm.input[0])
        users = cons.get(sm.output[0], [])
        if fl is None or fl.op_type != 'Flatten' or len(users) != 1 or users[0].op_type != 'Reshape':
            continue
        rs = users[0]
        sm.input[0] = fl.input[0]
        del sm.attribute[:]
        sm.attribute.append(helper.make_attribute('axis', -1))
        for c in cons.get(rs.output[0], []):
            for k, i in enumerate(c.input):
                if i == rs.output[0]:
                    c.input[k] = sm.output[0]
        for o in m.graph.output:
            if o.name == rs.output[0]:
                o.name = sm.output[0]
        drop |= {fl.name or id(fl), rs.name or id(rs)}
        count += 1
    keep = [n for n in m.graph.node if (n.name or id(n)) not in drop]
    del m.graph.node[:]
    m.graph.node.extend(keep)
    print('collapsed flatten/softmax/reshape:', count)
    return m


def fold_static(m, W):
    """Pins the input to 1x3xHxW, replaces every Shape op with its runtime answer (from ONNX Runtime),
    and constant-folds everything that then only depends on constants."""
    m2 = onnx.ModelProto()
    m2.CopyFrom(m)
    for d, v in zip(m2.graph.input[0].type.tensor_type.shape.dim, (1, 3, H, W)):
        d.Clear()
        d.dim_value = v
    probe = onnx.ModelProto()
    probe.CopyFrom(m2)
    have = {o.name for o in probe.graph.output}
    for t in {n.input[0] for n in m2.graph.node if n.op_type == 'Shape'} - have:
        vi = onnx.ValueInfoProto()
        vi.name = t
        probe.graph.output.append(vi)
    sess = ort.InferenceSession(probe.SerializeToString())
    runtime = dict(zip([o.name for o in sess.get_outputs()], sess.run(None, {'x': np.zeros((1, 3, H, W), np.float32)})))
    known = {i.name: numpy_helper.to_array(i) for i in m2.graph.initializer}
    keep = []
    for n in m2.graph.node:
        if n.op_type == 'Shape' and n.input[0] in runtime:
            known[n.output[0]] = np.array(runtime[n.input[0]].shape, dtype=np.int64)
            continue
        if n.op_type == 'Constant':
            known[n.output[0]] = numpy_helper.to_array(n.attribute[0].t)
            continue
        ins = [i for i in n.input if i]
        if ins and all(i in known for i in ins):
            g = helper.make_graph([n], 'f', [], [helper.make_tensor_value_info(o, 0, None) for o in n.output],
                                  initializer=[numpy_helper.from_array(np.asarray(known[i]), i) for i in set(ins)])
            for o, v in zip(n.output, ReferenceEvaluator(helper.make_model(g, opset_imports=m2.opset_import)).run(None, {})):
                known[o] = np.asarray(v)
            continue
        keep.append(n)
    used = {i for n in keep for i in n.input} | {o.name for o in m2.graph.output}
    del m2.graph.node[:]
    m2.graph.node.extend(keep)
    del m2.graph.initializer[:]
    m2.graph.initializer.extend(numpy_helper.from_array(np.asarray(v), k) for k, v in known.items() if k in used)
    return m2


base = collapse_flatten_softmax(load_opset13(src))
a, b = fold_static(base, 320), fold_static(base, 640)
ia = {i.name: numpy_helper.to_array(i) for i in a.graph.initializer}
ib = {i.name: numpy_helper.to_array(i) for i in b.graph.initializer}
assert set(ia) == set(ib), 'graphs folded differently at the two widths'
reshape_shapes = {n.input[1] for n in a.graph.node if n.op_type == 'Reshape'}
for name in ia:
    if ia[name].shape == ib[name].shape and np.array_equal(ia[name], ib[name]):
        continue
    diff = np.nonzero(ia[name] != ib[name])[0] if ia[name].shape == ib[name].shape else None
    if name in reshape_shapes and diff is not None and len(diff) == 1:
        v = ia[name].copy()
        v[diff[0]] = -1
        for i in a.graph.initializer:
            if i.name == name:
                i.CopyFrom(numpy_helper.from_array(v, name))
        print('width-dependent reshape', name, ia[name].tolist(), '->', v.tolist())
    else:
        raise SystemExit(f'width-dependent constant that is not a 1-dim reshape target: {name} {ia[name]} vs {ib[name]}')
# The input stays pinned at 320 for onnx2torch's own shape inference (it picks conv/pool/BN module
# classes from tensor rank); the resulting torch modules themselves no longer bake in the width.
a.ir_version = max(a.ir_version, 8)  # IR<4 only types initializers that are also graph inputs
del a.graph.value_info[:]  # stale, partly empty entries from the Paddle export block re-inference
a = onnx.shape_inference.infer_shapes(a, data_prop=True)
tmp = dst + '.tmp.onnx'
onnx.save(a, tmp)

orig = ort.InferenceSession(src)
tm = convert(tmp).eval()
os.remove(tmp)
for W in (320, 648, 1200, 96):
    x = (np.random.RandomState(W).rand(1, 3, H, W).astype(np.float32) * 2 - 1)
    with torch.no_grad():
        t = tm(torch.from_numpy(x)).numpy()
    r = orig.run(None, {'x': x})[0]
    print(f'torch W={W} maxdiff', float(np.abs(t - r).max()))

BUCKETS = [int(b) for b in os.environ.get('BUCKETS', '320,640,960,1280,1920').split(',')]
builder = None
for b in BUCKETS:
    xb = (torch.zeros(1, 3, H, b),)
    builder = (litert_torch.signature(f'w{b}', tm, xb) if builder is None else builder.signature(f'w{b}', tm, xb))
edge = builder.convert()
edge.export(dst)

from ai_edge_litert.interpreter import Interpreter
it = Interpreter(dst, num_threads=4)
sigs = it.get_signature_list()
print('signatures', {k: (v['inputs'], v['outputs']) for k, v in sigs.items()})
for b in BUCKETS:
    runner = it.get_signature_runner(f'w{b}')
    x = (np.random.RandomState(b).rand(1, 3, H, b).astype(np.float32) * 2 - 1)
    in_name = sigs[f'w{b}']['inputs'][0]
    t0 = time.time()
    out = list(runner(**{in_name: x}).values())[0]
    ms = (time.time() - t0) * 1000
    r = orig.run(None, {'x': x})[0]
    print(f'tflite w{b} out {out.shape} maxdiff {float(np.abs(out - r).max()):.2e} argmax {float((out.argmax(-1) == r.argmax(-1)).mean()):.4f} ms {ms:.1f}')
print('size', os.path.getsize(dst))
