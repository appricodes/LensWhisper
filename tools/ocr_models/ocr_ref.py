"""Reference OCR pipeline written exactly the way the Kotlin port will work (no OpenCV in the
post-processing), runnable on either ONNX Runtime (official models) or LiteRT (converted models),
so the two can be compared string-for-string on real photos.

usage: ocr_ref.py <lang> <image> [<image>...]"""
import sys, os, math, numpy as np, yaml
from PIL import Image

BASE = os.path.dirname(os.path.abspath(__file__))
DET_LIMIT = 960
DET_THRESH, BOX_THRESH, UNCLIP, MIN_SIZE = 0.3, 0.6, 1.5, 3
REC_H = 48
BUCKETS = [320, 640, 960, 1280, 1920]
DROP_SCORE = 0.5
MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)


def load_dict(lang):
    name = f'{lang}_PP-OCRv5_mobile_rec' if lang != 'ch' else 'PP-OCRv5_mobile_rec'
    y = yaml.safe_load(open(f'{BASE}/onnx/{name}/inference.yml', encoding='utf-8'))
    return ['<blank>'] + [str(c) for c in y['PostProcess']['character_dict']] + [' ']


# ------------------------------------------------------------------ runtimes
class OrtRuntime:
    def __init__(self, lang):
        import onnxruntime as ort
        name = f'{lang}_PP-OCRv5_mobile_rec' if lang != 'ch' else 'PP-OCRv5_mobile_rec'
        self.det = ort.InferenceSession(f'{BASE}/onnx/PP-OCRv5_mobile_det/inference.onnx')
        self.rec = ort.InferenceSession(f'{BASE}/onnx/{name}/inference.onnx')

    def run_det(self, nhwc):  # nhwc [1,H,W,3] -> prob [H,W]
        if os.environ.get('ORT_PAD') == '1':
            _, h, w, _ = nhwc.shape
            return self.det.run(None, {'x': pad_det(nhwc)})[0][0, 0, :h, :w]
        return self.det.run(None, {'x': nhwc.transpose(0, 3, 1, 2)})[0][0, 0]

    def run_rec(self, nchw):  # [1,3,48,W] -> [T,C]
        return self.rec.run(None, {'x': nchw})[0][0]


DET_SIZES = [(960, 960), (960, 736), (736, 960), (960, 544), (544, 960)]


def det_bucket(h, w):
    fits = [(bh, bw) for bh, bw in DET_SIZES if bh >= h and bw >= w]
    return min(fits, key=lambda s: s[0] * s[1])


def pad_det(nhwc):
    """Pads the normalized image (zeros = the mean colour) to its det bucket, NCHW."""
    _, h, w, _ = nhwc.shape
    bh, bw = det_bucket(h, w)
    out = np.zeros((1, 3, bh, bw), np.float32)
    out[0, :, :h, :w] = nhwc[0].transpose(2, 0, 1)
    return out


class LiteRuntime:
    def __init__(self, lang, det_path, rec_path):
        from ai_edge_litert.interpreter import Interpreter
        self.det = Interpreter(det_path, num_threads=4)
        self.rec = Interpreter(rec_path, num_threads=4)
        self.dsigs = self.det.get_signature_list()
        self.sigs = self.rec.get_signature_list()

    def run_det(self, nhwc):
        _, h, w, _ = nhwc.shape
        x = pad_det(nhwc)
        sig = f'd{x.shape[2]}x{x.shape[3]}'
        out = list(self.det.get_signature_runner(sig)(**{self.dsigs[sig]['inputs'][0]: x}).values())[0]
        return out[0, 0, :h, :w]

    def run_rec(self, nchw):
        sig = f'w{nchw.shape[3]}'
        r = self.rec.get_signature_runner(sig)
        return list(r(**{self.sigs[sig]['inputs'][0]: nchw}).values())[0][0]


# ------------------------------------------------------------------ geometry
def convex_hull(pts):
    pts = sorted(set(pts))
    if len(pts) <= 2:
        return pts
    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def min_area_rect(hull):
    """Returns (cx, cy, w, h, ux, uy): centre, side lengths, and the unit vector of the w side."""
    if len(hull) == 1:
        return hull[0][0], hull[0][1], 0.0, 0.0, 1.0, 0.0
    best = None
    n = len(hull)
    for i in range(n):
        x0, y0 = hull[i]
        x1, y1 = hull[(i + 1) % n]
        ex, ey = x1 - x0, y1 - y0
        ln = math.hypot(ex, ey)
        if ln == 0:
            continue
        ux, uy = ex / ln, ey / ln
        vx, vy = -uy, ux
        us = [(p[0] * ux + p[1] * uy) for p in hull]
        vs = [(p[0] * vx + p[1] * vy) for p in hull]
        w, h = max(us) - min(us), max(vs) - min(vs)
        if best is None or w * h < best[0]:
            cu, cv = (max(us) + min(us)) / 2, (max(vs) + min(vs)) / 2
            best = (w * h, cu * ux + cv * vx, cu * uy + cv * vy, w, h, ux, uy)
    return best[1:]


def rect_corners(cx, cy, w, h, ux, uy):
    """Corners ordered top-left, top-right, bottom-right, bottom-left (image coordinates)."""
    vx, vy = -uy, ux
    pts = [(cx + sx * w / 2 * ux + sy * h / 2 * vx, cy + sx * w / 2 * uy + sy * h / 2 * vy)
           for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
    # Same ordering rule as PaddleOCR's get_mini_boxes: sort by x, then split left/right pairs by y.
    pts.sort(key=lambda p: p[0])
    l = sorted(pts[:2], key=lambda p: p[1])
    r = sorted(pts[2:], key=lambda p: p[1])
    return [l[0], r[0], r[1], l[1]]


def box_score(prob, quad):
    h, w = prob.shape
    xs, ys = [p[0] for p in quad], [p[1] for p in quad]
    x0, x1 = max(0, int(math.floor(min(xs)))), min(w - 1, int(math.ceil(max(xs))))
    y0, y1 = max(0, int(math.floor(min(ys)))), min(h - 1, int(math.ceil(max(ys))))
    total, count = 0.0, 0
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            inside = True
            for i in range(4):
                ax, ay = quad[i]
                bx, by = quad[(i + 1) % 4]
                if (bx - ax) * (y - ay) - (by - ay) * (x - ax) < 0:
                    inside = False
                    break
            if inside:
                total += prob[y, x]
                count += 1
    return total / count if count else 0.0


def components(bitmap):
    h, w = bitmap.shape
    label = np.zeros((h, w), np.int32)
    comps = []
    nid = 0
    for sy in range(h):
        row = bitmap[sy]
        for sx in np.nonzero(row)[0]:
            if label[sy, sx]:
                continue
            nid += 1
            stack = [(sy, sx)]
            label[sy, sx] = nid
            boundary = []
            while stack:
                y, x = stack.pop()
                edge = False
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        if dy == 0 and dx == 0:
                            continue
                        yy, xx = y + dy, x + dx
                        if 0 <= yy < h and 0 <= xx < w and bitmap[yy, xx]:
                            if not label[yy, xx]:
                                label[yy, xx] = nid
                                stack.append((yy, xx))
                        elif dy == 0 or dx == 0:
                            edge = True
                if edge:
                    boundary.append((x, y))
            comps.append(boundary)
    return comps


# ------------------------------------------------------------------ pipeline
def detect(rt, img):
    w0, h0 = img.size
    ratio = DET_LIMIT / max(w0, h0)
    rh = max(32, int(round(int(h0 * ratio) / 32) * 32))
    rw = max(32, int(round(int(w0 * ratio) / 32) * 32))
    arr = np.asarray(img.convert('RGB').resize((rw, rh), Image.BILINEAR), np.float32)[:, :, ::-1] / 255.0  # BGR
    arr = (arr - MEAN) / STD
    prob = rt.run_det(arr[None].astype(np.float32))
    ph, pw = prob.shape
    boxes = []
    for boundary in components(prob > DET_THRESH)[:1000]:
        hull = convex_hull(boundary)
        cx, cy, w, h, ux, uy = min_area_rect(hull)
        if min(w, h) < MIN_SIZE:
            continue
        if box_score(prob, rect_corners(cx, cy, w, h, ux, uy)) < BOX_THRESH:
            continue
        d = w * h * UNCLIP / (2 * (w + h))
        w, h = w + 2 * d, h + 2 * d
        if min(w, h) < MIN_SIZE + 2:
            continue
        sx, sy = w0 / pw, h0 / ph
        quad = [(min(max(x * sx, 0), w0), min(max(y * sy, 0), h0)) for x, y in rect_corners(cx, cy, w, h, ux, uy)]
        boxes.append(quad)
    return boxes


def crop_line(img, quad):
    tl, tr, br, bl = quad
    cw = max(math.dist(tl, tr), math.dist(bl, br))
    ch = max(math.dist(tl, bl), math.dist(tr, br))
    rotate = ch / max(cw, 1e-6) >= 1.5
    if rotate:  # vertical line: PaddleOCR rotates it 90 degrees counter-clockwise
        tl, tr, br, bl = tr, br, bl, tl
        cw, ch = ch, cw
    tw = max(1, int(math.ceil(REC_H * cw / max(ch, 1e-6))))
    bucket = next((b for b in BUCKETS if b >= tw), BUCKETS[-1])
    tw = min(tw, bucket)
    # Perspective warp of the quad straight to tw x 48, bilinear (PIL's QUAD transform).
    line = img.convert('RGB').transform((tw, REC_H), Image.QUAD, data=(*tl, *bl, *br, *tr), resample=Image.BILINEAR)
    arr = np.asarray(line, np.float32)[:, :, ::-1] / 255.0
    arr = (arr - 0.5) / 0.5
    out = np.zeros((1, 3, REC_H, bucket), np.float32)
    out[0, :, :, :tw] = arr.transpose(2, 0, 1)
    return out


def ctc(logits, chars):
    idx = logits.argmax(-1)
    prob = logits.max(-1)
    text, scores, prev = [], [], -1
    for i, p in zip(idx, prob):
        if i != prev and i != 0:
            text.append(chars[i])
            scores.append(p)
        prev = i
    return ''.join(text), float(np.mean(scores)) if scores else 0.0


LTR_RUN = set('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 :*./%+-'
              '٠١٢٣٤٥٦٧٨٩'
              '۰۱۲۳۴۵۶۷۸۹')


def visual_to_logical_rtl(text):
    """PaddleOCR's pred_reverse for right-to-left scripts: CTC reads the line left to right (visual
    order), so reverse it, keeping runs of digits/Latin (including Persian and Arabic-Indic digits,
    which PaddleOCR's own regex misses) in their original order."""
    parts, run = [], ''
    for c in text:
        if c in LTR_RUN:
            run += c
        else:
            if run:
                parts.append(run)
            parts.append(c)
            run = ''
    if run:
        parts.append(run)
    return ''.join(reversed(parts))


def run(rt, img, chars, rtl=False):
    lines = []
    for quad in detect(rt, img):
        text, score = ctc(rt.run_rec(crop_line(img, quad)), chars)
        if rtl:
            text = visual_to_logical_rtl(text)
        if score >= DROP_SCORE and text.strip():
            lines.append((quad, text, score))
    lines.sort(key=lambda l: (min(p[1] for p in l[0]), min(p[0] for p in l[0])))
    return lines


if __name__ == '__main__':
    lang = sys.argv[1]
    chars = load_dict(lang)
    ort_rt = OrtRuntime(lang)
    lite_rt = LiteRuntime(lang, os.environ.get('LITE_DET', f'{BASE}/tfl_torch/det_ms.tflite'), os.environ.get('LITE_REC', f'{BASE}/tfl_torch/ms_{lang}.tflite')) if os.environ.get('LITE', '1') == '1' else None
    for path in sys.argv[2:]:
        img = Image.open(path)
        a = run(ort_rt, img, chars, rtl=lang == 'arabic')
        print(f'=== {os.path.basename(path)}  {img.size}  ORT lines={len(a)}')
        for _, t, s in a:
            print(f'   {s:.2f}  {t}')
        if lite_rt:
            b = run(lite_rt, img, chars, rtl=lang == 'arabic')
            same = [x[1] for x in a] == [x[1] for x in b]
            print(f'    LiteRT lines={len(b)}  identical_text={same}')
            if not same:
                for x, y in zip(a, b):
                    if x[1] != y[1]:
                        print('      ORT :', x[1]); print('      LITE:', y[1])
