import sys
import numpy as np
from PIL import Image, ImageFilter
# cutout.py <in> <out> <lock height fraction>
# Separates the yellow padlock from its near-white background using color
# saturation (fur ~0.8, background ~0.02), then centers it on a transparent
# 1024 canvas at the given height.
src = Image.open(sys.argv[1]).convert("RGB")
rgb = np.asarray(src).astype(np.float32) / 255
mx, mn = rgb.max(axis=2), rgb.min(axis=2)
sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)
# The floor shadow under the lock is a pale beige (saturation ~0.1-0.3);
# start the ramp above it so only the fur is kept.
alpha = np.clip((sat - 0.16) / 0.24, 0, 1)
# Edge cleanup: semi-transparent edge pixels keep the photo's grayish fur
# tips. Recolor them with the local fur color (an alpha-weighted blur of the
# solid fur around them) so edges fade out yellow, not gray.
def box(a, r, axis):
    pad = [(0, 0)] * a.ndim; pad[axis] = (r + 1, r)
    c = np.cumsum(np.pad(a, pad, mode="edge"), axis=axis)
    n = a.shape[axis]
    hi = np.take(c, np.arange(2 * r + 1, 2 * r + 1 + n), axis=axis)
    lo = np.take(c, np.arange(0, n), axis=axis)
    return (hi - lo) / (2 * r + 1)
def blur(a, r=4):
    for _ in range(3):                                  # 3 box passes ~ Gaussian
        a = box(box(a, r, 0), r, 1)
    return a
solid = np.clip((alpha - 0.6) / 0.4, 0, 1)            # weight: only well-inside fur
weight = blur(solid)
local = np.dstack([blur(rgb[..., i] * solid) for i in range(3)]) / np.maximum(weight, 1e-4)[..., None]
edge = (alpha < 0.98)[..., None] & (weight > 1e-3)[..., None]
rgb = np.where(edge, local, rgb)
ys, xs = np.where(alpha > 0.5)
y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
rgba = np.dstack([rgb, alpha])[y0:y1, x0:x1]
lock = Image.fromarray((rgba * 255).round().astype(np.uint8), "RGBA")
target = int(1024 * float(sys.argv[3]))
scale = target / lock.height
lock = lock.resize((round(lock.width * scale), target), Image.LANCZOS)
canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
canvas.paste(lock, ((1024 - lock.width) // 2, (1024 - lock.height) // 2), lock)
canvas.save(sys.argv[2])
print(f"lock {x1 - x0}x{y1 - y0} -> {lock.width}x{lock.height}")
