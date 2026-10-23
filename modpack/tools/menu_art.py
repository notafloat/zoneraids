#!/usr/bin/env python3
"""Draws the title-screen panorama and the menu background of the Zone Raids mod.

Overcast Zone sky, haze, a dark tree line with ruins, a water tower and power line poles. The faces are
computed from view directions, and everything near the cube edges depends only on elevation, so the
cube is seamless however the game orders and orients the faces. 512 px faces keep VRAM use small.

    python3 modpack/tools/menu_art.py
"""
import os

import numpy as np
from PIL import Image

N = 512
ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "minecraft",
                    "textures", "gui")
rng = np.random.default_rng(1986)


def smooth(edge0, edge1, x):
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def value_noise_1d(x, period, seed):
    r = np.random.default_rng(seed).random(4096)
    xi = np.floor(x / period).astype(int)
    f = x / period - xi
    f = f * f * (3 - 2 * f)
    return r[xi % 4096] * (1 - f) + r[(xi + 1) % 4096] * f


def value_noise_2d(x, y, period, seed):
    r = np.random.default_rng(seed).random((256, 256))
    xs, ys = x / period, y / period
    xi, yi = np.floor(xs).astype(int), np.floor(ys).astype(int)
    fx, fy = xs - xi, ys - yi
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a = r[yi % 256, xi % 256]
    b = r[yi % 256, (xi + 1) % 256]
    c = r[(yi + 1) % 256, xi % 256]
    d = r[(yi + 1) % 256, (xi + 1) % 256]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def fbm(x, y, period, seed, octaves=4):
    total, amp, norm = 0.0, 1.0, 0.0
    for i in range(octaves):
        total = total + amp * value_noise_2d(x, y, period / 2 ** i, seed + i)
        norm += amp
        amp *= 0.5
    return total / norm


SKY_HORIZON = np.array([152, 142, 108], float)
SKY_MID = np.array([104, 102, 84], float)
SKY_TOP = np.array([62, 64, 56], float)
GROUND_NEAR = np.array([30, 31, 24], float)
GROUND_HAZE = np.array([74, 72, 56], float)
SILHOUETTE = np.array([22, 24, 18], float)


def lerp(a, b, t):
    return a + (b - a) * t[..., None]


def sky(elev):
    """Sky colour from elevation (degrees) only."""
    low = lerp(SKY_HORIZON, SKY_MID, smooth(0, 18, elev))
    return lerp(low, SKY_TOP, smooth(18, 75, elev))


def ground(elev):
    return lerp(GROUND_HAZE, GROUND_NEAR, smooth(-1, -25, elev))


def side_face(index):
    v, u = np.mgrid[0:N, 0:N].astype(float)
    u = (u + 0.5) / N * 2 - 1
    v = (v + 0.5) / N * 2 - 1
    az = np.degrees(np.arctan(u))  # -45..45 inside the face
    elev = np.degrees(np.arctan2(-v, np.sqrt(1 + u * u)))
    window = 1 - smooth(32, 44.5, np.abs(az))  # 0 at the face edges: no seams whatever the order

    img = np.where((elev >= 0)[..., None], sky(elev), ground(elev))

    # Ragged cloud band near the horizon, faded out above 30 degrees and at the face edges.
    clouds = fbm(az * 6 + index * 1000, elev * 9, 90, 10 + index)
    cloud_w = smooth(2, 8, elev) * (1 - smooth(18, 30, elev)) * window
    img = lerp(img, np.array([84, 82, 70], float), np.clip((clouds - 0.4) * 2.5, 0, 1) * cloud_w * 0.9)

    # Tree line: height in degrees as a function of azimuth.
    x = (az + 45) / 90 * N + index * N
    trees = 2.0 + 4.2 * value_noise_1d(x, 22, 3) + 1.8 * value_noise_1d(x, 5, 4) + 0.8 * value_noise_1d(x, 2, 5)
    height = 3.0 + (trees - 3.0) * window
    col = np.arange(N)
    height_cols = height[0]

    def block(c0, c1, top):
        sel = (col >= c0) & (col < c1)
        height_cols[sel] = np.maximum(height_cols[sel], top)

    mask_extra = np.zeros((N, N), bool)
    if index == 0:  # ruined farm buildings and a water tower
        block(150, 230, 9.0)
        block(175, 205, 11.0)
        block(240, 290, 7.5)
        block(330, 336, 18.0)
        block(338, 344, 18.0)
        top_v = -np.tan(np.radians(18.0)) * np.sqrt(1 + u * u)
        tank = (u * N / 2 + N / 2 > 318) & (u * N / 2 + N / 2 < 356) & (v > top_v - 0.16) & (v < top_v)
        mask_extra |= tank
    elif index == 1:  # power line poles with sagging wires
        poles = [110, 260, 410]
        for p in poles:
            block(p, p + 3, 15.5)
            yv = -np.tan(np.radians(15.0)) * np.sqrt(1 + u * u)
            arm = (np.abs(col[None, :] - (p + 1)) < 9) & (np.abs(v - yv) < 0.004)
            mask_extra |= arm
        for a, b in zip(poles, poles[1:]):
            for off in (-8, 8):
                xs = np.arange(a + 1 + off, b + 1 + off)
                t = (xs - xs[0]) / (xs[-1] - xs[0])
                sag = 0.035 * 4 * t * (1 - t)
                yv = -np.tan(np.radians(15.0)) + sag
                rows = ((yv + 1) / 2 * N).astype(int)
                mask_extra[rows, xs] = True
                mask_extra[np.minimum(rows + 1, N - 1), xs] = True
    elif index == 2:  # dense forest
        height_cols[:] = 3.0 + (height_cols - 3.0) * 1.4
    else:  # broken checkpoint wall and a radio mast
        block(120, 300, 6.0)
        for c in range(130, 300, 23):
            block(c, c + 9, 4.5)
        block(380, 383, 24.0)
        for k in range(1, 6):
            yv = -np.tan(np.radians(24.0 * k / 6))
            r = int((yv + 1) / 2 * N)
            mask_extra[r, 376:388] = True

    sil = (elev >= -0.5) & (elev < height_cols[None, :]) | mask_extra
    fog = smooth(0, 14, elev)[..., None] * 0.25
    sil_col = SILHOUETTE * (1 - fog) + SKY_HORIZON * fog
    img = np.where(sil[..., None], sil_col, img)

    # Faint field texture below the horizon, also faded at the edges.
    field = fbm(az * 10 + index * 500, elev * 14, 40, 30 + index)
    img = img * (1 + ((field - 0.5) * 0.35 * window * smooth(0, -6, elev))[..., None])
    return img


def cap_face(top):
    v, u = np.mgrid[0:N, 0:N].astype(float)
    u = (u + 0.5) / N * 2 - 1
    v = (v + 0.5) / N * 2 - 1
    elev = np.degrees(np.arctan2(1.0, np.sqrt(u * u + v * v)))
    return sky(elev) if top else ground(-elev)


def finish(img):
    grain = rng.normal(0, 2.2, img.shape[:2])[..., None]
    return Image.fromarray(np.clip(img + grain, 0, 255).astype(np.uint8), "RGB")


def background_tile():
    """16x16 dark concrete with rust specks, tiled behind lists and options (vanilla draws it darkened)."""
    y, x = np.mgrid[0:16, 0:16].astype(float)
    n = fbm(x * 16, y * 16, 64, 77, 3)
    # Tile-seamless: average with the wrapped copy.
    n = (n + np.roll(n, 8, 0) + np.roll(n, 8, 1) + np.roll(np.roll(n, 8, 0), 8, 1)) / 4
    base = np.array([96, 94, 80], float)
    img = base * (0.75 + 0.5 * n)[..., None]
    specks = rng.random((16, 16)) > 0.93
    img[specks] = img[specks] * 0.6 + np.array([120, 70, 40]) * 0.4
    img[0, :] *= 0.85
    img[:, 0] *= 0.85
    return finish(img)


def main():
    pano = os.path.join(ROOT, "title", "background")
    os.makedirs(pano, exist_ok=True)
    for i in range(4):
        finish(side_face(i)).save(os.path.join(pano, f"panorama_{i}.png"), optimize=True)
    finish(cap_face(True)).save(os.path.join(pano, "panorama_4.png"), optimize=True)
    finish(cap_face(False)).save(os.path.join(pano, "panorama_5.png"), optimize=True)
    background_tile().save(os.path.join(ROOT, "options_background.png"), optimize=True)


if __name__ == "__main__":
    main()
