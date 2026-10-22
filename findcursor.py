#!/usr/bin/env python3
"""Deteksi posisi cursor via diff dua screenshot raw RGBA (screencap tanpa -p).

Pemakaian:
  python3 findcursor.py _a.raw _b.raw
"""
import sys
import numpy as np

W, H = 1280, 720


def load(path):
    buf = np.fromfile(path, dtype=np.uint8)
    # screencap raw = header 16 byte (w,h,format,bpp semua int32 LE) lalu RGBA
    hdr = buf[:16].view(np.int32)
    w, h = int(hdr[0]), int(hdr[1])
    img = buf[16:].reshape(h, w, 4)[:, :, :3]  # RGBA -> RGB
    return img.astype(np.int16)


def main():
    if len(sys.argv) != 3:
        print("usage: findcursor.py a.raw b.raw")
        return 1
    a = load(sys.argv[1])
    b = load(sys.argv[2])
    diff = np.abs(a - b).sum(axis=2)
    mask = diff > 30
    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        print("TIDAK ADA PERBEDAAN (cursor tidak bergerak / tidak tergambar)")
        return 2
    x0, x1 = int(xs.min()), int(xs.max())
    y0, y1 = int(ys.min()), int(ys.max())
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    print(f"bbox=({x0},{y0})-({x1},{y1}) size={x1-x0+1}x{y1-y0+1} center=({cx},{cy})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
