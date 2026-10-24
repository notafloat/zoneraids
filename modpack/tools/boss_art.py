#!/usr/bin/env python3
"""Textures (and preview renders) of the survival bosses: pseudogiant, controller and Borov's skin.

The box lists below mirror the Java models (PseudogiantModel, ControllerModel) one to one: texture
offset, box origin and size. Change both together. Borov is a TaCZ NPC, so his texture is a regular
player skin.

  python3 modpack/tools/boss_art.py                      # writes the textures into the mod's assets
  python3 modpack/tools/boss_art.py --preview out_dir    # also renders a preview of each model

Needs Pillow. The art is procedural and seeded, so a run always gives the same pictures.
"""
import argparse
import math
import os
import random

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "zoneraids", "textures", "entity")


# ---------------------------------------------------------------- models (same numbers as the Java models)

class Box:
    def __init__(self, u, v, x, y, z, w, h, d, mat, mirror=False, detail=None):
        self.u, self.v = u, v
        self.x, self.y, self.z = x, y, z
        self.w, self.h, self.d = w, h, d
        self.mat, self.mirror, self.detail = mat, mirror, detail

    # Texture regions of the faces, as MC's ModelPart.Cube lays them out.
    def top(self):
        return self.u + self.d, self.v, self.w, self.d

    def bottom(self):
        return self.u + self.d + self.w, self.v, self.w, self.d

    def front(self):
        return self.u + self.d, self.v + self.d, self.w, self.h

    def back(self):
        return self.u + 2 * self.d + self.w, self.v + self.d, self.w, self.h

    def side_min_x(self):
        return self.u, self.v + self.d, self.d, self.h

    def side_max_x(self):
        return self.u + self.d + self.w, self.v + self.d, self.d, self.h


class Part:
    def __init__(self, name, pivot, rot=(0.0, 0.0, 0.0), boxes=(), children=()):
        self.name, self.pivot, self.rot = name, pivot, rot
        self.boxes, self.children = list(boxes), list(children)


def pseudogiant_model():
    head = Part("head", (0, 1, -11), (0.15, 0, 0), [
        Box(72, 40, -5, -4, -7, 10, 8, 7, "head", detail="pseudogiant_face"),
    ], [Part("jaw", (0, 2, -1), (0.1, 0, 0), [Box(72, 62, -4.5, 0, -6, 9, 3, 6, "flesh", detail="teeth")])])
    body = Part("body", (0, 6, 0), boxes=[
        Box(0, 0, -13, -9, -11, 26, 18, 22, "flesh", detail="belly"),
        Box(0, 40, -10, -15, -7, 20, 6, 16, "hump"),
    ], children=[
        head,
        Part("arm_left", (-12, -2, -7), (-0.3, 0, 0.2), [Box(40, 62, -3, -1, -3, 5, 11, 5, "flesh", detail="claw")]),
        Part("arm_right", (12, -1, -6), (-0.2, 0, -0.25), [Box(64, 72, -2, -1, -2, 4, 7, 4, "flesh", detail="claw")]),
    ])
    legs = [Part("leg_" + side, (sx * 7, 14, 2), boxes=[
        Box(0, 62, -4.5, 0, -4.5, 9, 10, 9, "leg", mirror=sx > 0),
        Box(0, 81, -4.5, 8, -6.5, 9, 2, 2, "toes", mirror=sx > 0),
    ]) for side, sx in (("left", -1), ("right", 1))]
    return [body] + legs, 128, 128


def controller_model():
    head = Part("head", (0, 2.5, 0), (0.2, 0, 0), [
        Box(0, 0, -5, -10, -5.5, 10, 10, 9, "pale", detail="controller_face"),
        Box(38, 0, -3.5, -12.5, -3.5, 7, 3, 6, "brain"),
    ])
    body = Part("body", (0, 2, 1), (0.35, 0, 0), [
        Box(0, 19, -4, 0, -2, 8, 11, 4, "shirt"),
        Box(0, 37, -4.5, 7, -2.5, 9, 5, 5, "rags", detail="torn"),
    ])
    arms = [Part("arm_" + side, (sx * 5.5, 4, -1), (-0.4, 0, -sx * 0.1), [
        Box(24, 19, -1.5, -1, -1.5, 3, 15, 3, "arm", mirror=sx > 0, detail="hand"),
    ]) for side, sx in (("right", -1), ("left", 1))]
    legs = [Part("leg_" + side, (sx * 2, 12, 3), boxes=[
        Box(36, 19, -2, 0, -2, 4, 12, 4, "trousers", mirror=sx > 0, detail="boots"),
    ]) for side, sx in (("right", -1), ("left", 1))]
    return [body, head] + arms + legs, 64, 64


def player_model():
    """Vanilla player boxes (wide arms), only for painting and previewing Borov's skin."""
    return [
        Part("head", (0, 0, 0), boxes=[Box(0, 0, -4, -8, -4, 8, 8, 8, "borov_head", detail="borov_face")]),
        Part("body", (0, 0, 0), boxes=[Box(16, 16, -4, 0, -2, 8, 12, 4, "jacket", detail="jacket_front")]),
        Part("arm_right", (-5, 2, 0), boxes=[Box(40, 16, -3, -2, -2, 4, 12, 4, "jacket_arm", detail="fist")]),
        Part("arm_left", (5, 2, 0), boxes=[Box(32, 48, -1, -2, -2, 4, 12, 4, "jacket_arm", detail="fist")]),
        Part("leg_right", (-1.9, 12, 0), boxes=[Box(0, 16, -2, 0, -2, 4, 12, 4, "jeans", detail="boots")]),
        Part("leg_left", (1.9, 12, 0), boxes=[Box(16, 48, -2, 0, -2, 4, 12, 4, "jeans", detail="boots")]),
    ], 64, 64


# ---------------------------------------------------------------- painting

PALETTE = {
    # base, dark, light
    "flesh": ((168, 118, 108), (118, 72, 70), (196, 150, 136)),
    "hump": ((150, 104, 98), (104, 62, 62), (182, 136, 122)),
    "head": ((160, 108, 100), (100, 58, 58), (190, 140, 126)),
    "leg": ((132, 92, 86), (90, 58, 56), (160, 120, 108)),
    "toes": ((70, 58, 50), (40, 32, 28), (96, 82, 70)),
    "pale": ((176, 172, 150), (120, 116, 100), (204, 200, 178)),
    "brain": ((184, 150, 150), (128, 86, 96), (212, 182, 178)),
    "arm": ((168, 164, 142), (114, 110, 94), (196, 192, 170)),
    "shirt": ((72, 70, 60), (44, 42, 36), (96, 94, 80)),
    "rags": ((84, 74, 58), (52, 44, 34), (110, 98, 78)),
    "trousers": ((62, 66, 58), (38, 40, 36), (86, 90, 78)),
    "borov_head": ((198, 150, 122), (150, 104, 84), (222, 178, 150)),
    "jacket": ((46, 38, 34), (26, 22, 20), (74, 62, 54)),
    "jacket_arm": ((46, 38, 34), (26, 22, 20), (74, 62, 54)),
    "jeans": ((50, 58, 74), (30, 36, 48), (72, 82, 100)),
}


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c)


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def fill_region(px, rect, mat, rng, light=1.0):
    base, dark, hi = PALETTE[mat]
    x0, y0, w, h = rect
    for y in range(int(y0), int(y0 + h)):
        for x in range(int(x0), int(x0 + w)):
            r = rng.random()
            c = base
            if r < 0.18:
                c = mix(base, dark, 0.5 + rng.random() * 0.5)
            elif r > 0.9:
                c = mix(base, hi, 0.6)
            c = shade(c, light * (0.94 + rng.random() * 0.12))
            px[x, y] = c + (255,)


def blotches(px, rect, mat, rng, count, size=2):
    _, dark, _ = PALETTE[mat]
    x0, y0, w, h = rect
    for _ in range(count):
        cx, cy = x0 + rng.random() * w, y0 + rng.random() * h
        for y in range(int(cy - size), int(cy + size + 1)):
            for x in range(int(cx - size), int(cx + size + 1)):
                if x0 <= x < x0 + w and y0 <= y < y0 + h and (x - cx) ** 2 + (y - cy) ** 2 <= size * size * rng.uniform(0.5, 1.2):
                    px[x, y] = mix(px[x, y][:3], dark, 0.55) + (255,)


def veins(px, rect, color, rng, count, length=8):
    x0, y0, w, h = rect
    for _ in range(count):
        x, y = x0 + rng.random() * w, y0 + rng.random() * h
        angle = rng.random() * math.pi * 2
        for _ in range(length):
            if x0 <= x < x0 + w and y0 <= y < y0 + h:
                px[int(x), int(y)] = color + (255,)
            angle += rng.uniform(-0.7, 0.7)
            x += math.cos(angle)
            y += math.sin(angle)


def put(px, x, y, color):
    px[int(x), int(y)] = (color + (255,)) if len(color) == 3 else color


def paint_box(px, box, rng):
    for rect, light in ((box.top(), 1.12), (box.bottom(), 0.72), (box.front(), 1.0), (box.back(), 0.9),
                        (box.side_min_x(), 0.95), (box.side_max_x(), 0.95)):
        fill_region(px, rect, box.mat, rng, light)
    if box.mat in ("flesh", "hump", "head", "leg"):
        for rect in (box.top(), box.front(), box.back(), box.side_min_x(), box.side_max_x()):
            blotches(px, rect, box.mat, rng, max(1, int(rect[2] * rect[3] / 60)), 2)
            veins(px, rect, (110, 48, 56), rng, max(1, int(rect[2] * rect[3] / 90)))
    if box.mat in ("pale", "arm"):
        for rect in (box.front(), box.side_min_x(), box.side_max_x(), box.back()):
            blotches(px, rect, box.mat, rng, max(1, int(rect[2] * rect[3] / 50)), 1)
    if box.mat == "brain":
        for rect in (box.top(), box.front(), box.back(), box.side_min_x(), box.side_max_x()):
            veins(px, rect, (120, 60, 88), rng, max(2, int(rect[2] * rect[3] / 12)), 6)
    DETAILS.get(box.detail, lambda *a: None)(px, box, rng)


def detail_belly(px, box, rng):
    # Folds of skin across the front and a few open sores.
    x0, y0, w, h = box.front()
    for fold in (h * 0.45, h * 0.7):
        for x in range(x0, x0 + w):
            y = int(y0 + fold + math.sin(x * 0.7) * 0.8)
            put(px, x, y, (112, 70, 66))
            put(px, x, y - 1, (190, 146, 132))
    for _ in range(4):
        x, y = x0 + rng.randrange(2, w - 2), y0 + rng.randrange(2, h - 2)
        put(px, x, y, (120, 28, 30))
        put(px, x + 1, y, (150, 40, 36))


def detail_pseudogiant_face(px, box, rng):
    x0, y0, w, h = box.front()
    # Small, deep-set eyes, a heavy brow and nostril slits.
    for x in range(x0, x0 + w):
        put(px, x, y0 + 2, (104, 62, 60))
    for ex in (x0 + 2, x0 + w - 4):
        put(px, ex, y0 + 3, (30, 14, 12))
        put(px, ex + 1, y0 + 3, (190, 40, 30))
    put(px, x0 + w // 2 - 1, y0 + 5, (70, 30, 30))
    put(px, x0 + w // 2, y0 + 5, (70, 30, 30))


def detail_teeth(px, box, rng):
    x0, y0, w, h = box.front()
    for x in range(x0, x0 + w):
        put(px, x, y0, (40, 18, 18))
        if x % 2 == 0:
            put(px, x, y0, (214, 204, 170))
    tx, ty, tw, td = box.top()
    for x in range(tx, tx + tw):
        put(px, x, ty + td - 1, (214, 204, 170) if x % 2 else (90, 30, 30))
    for y in range(ty, ty + td - 1):
        for x in range(tx + 1, tx + tw - 1):
            put(px, x, y, (110, 32, 34))


def detail_claw(px, box, rng):
    bx, by, bw, bd = box.bottom()
    for x in range(bx, bx + bw):
        for y in range(by, by + bd):
            put(px, x, y, (58, 46, 40) if (x + y) % 2 else (84, 70, 60))
    x0, y0, w, h = box.front()
    for x in range(x0, x0 + w):
        put(px, x, y0 + h - 1, (64, 52, 44))


def detail_controller_face(px, box, rng):
    x0, y0, w, h = box.front()
    # Bulging bald skull with sunken, glowing eyes; small slit mouth.
    for ex in (x0 + 2, x0 + w - 4):
        for dx in range(2):
            put(px, ex + dx, y0 + 5, (40, 34, 30))
            put(px, ex + dx, y0 + 6, (212, 228, 140))
        put(px, ex, y0 + 4, (120, 116, 100))
    for x in range(x0 + 3, x0 + w - 3):
        put(px, x, y0 + 8, (70, 50, 48))
    veins(px, (x0, y0, w, 4), (128, 86, 110), rng, 4, 5)


def detail_torn(px, box, rng):
    # Ragged lower edge with holes the cutout renderer leaves open.
    for rect in (box.front(), box.back(), box.side_min_x(), box.side_max_x()):
        x0, y0, w, h = rect
        for x in range(x0, x0 + w):
            depth = rng.randrange(0, 3)
            for y in range(y0 + h - depth, y0 + h):
                px[x, y] = (0, 0, 0, 0)
        for _ in range(2):
            px[x0 + rng.randrange(w), y0 + rng.randrange(max(1, h - 2))] = (0, 0, 0, 0)


def detail_hand(px, box, rng):
    for rect in (box.front(), box.back(), box.side_min_x(), box.side_max_x()):
        x0, y0, w, h = rect
        for y in range(y0 + h - 3, y0 + h):
            for x in range(x0, x0 + w):
                put(px, x, y, (150, 140, 120) if (x + y) % 3 else (118, 104, 92))
    bx, by, bw, bd = box.bottom()
    for x in range(bx, bx + bw):
        for y in range(by, by + bd):
            put(px, x, y, (96, 84, 74))


def detail_boots(px, box, rng):
    for rect in (box.front(), box.back(), box.side_min_x(), box.side_max_x()):
        x0, y0, w, h = rect
        for y in range(y0 + h - 3, y0 + h):
            for x in range(x0, x0 + w):
                put(px, x, y, (34, 28, 24) if rng.random() > 0.2 else (54, 44, 36))
    bx, by, bw, bd = box.bottom()
    for x in range(bx, bx + bw):
        for y in range(by, by + bd):
            put(px, x, y, (26, 22, 20))


def detail_borov_face(px, box, rng):
    x0, y0, w, h = box.front()
    # Shaved head, heavy brow, small eyes, a broken nose, stubble and a scar over the left eye.
    for x in range(x0 + 1, x0 + 7):
        put(px, x, y0 + 3, (150, 104, 84))
    for ex in (x0 + 1, x0 + 5):
        put(px, ex, y0 + 4, (240, 236, 226))
        put(px, ex + 1, y0 + 4, (52, 40, 30))
    put(px, x0 + 3, y0 + 5, (176, 124, 100))
    put(px, x0 + 4, y0 + 5, (176, 124, 100))
    for y in range(y0 + 5, y0 + 8):
        for x in range(x0, x0 + 8):
            if rng.random() < 0.5:
                put(px, x, y, (128, 98, 80))
    for x in range(x0 + 2, x0 + 6):
        put(px, x, y0 + 6, (110, 52, 48))
    put(px, x0 + 5, y0 + 2, (168, 92, 84))
    put(px, x0 + 6, y0 + 3, (168, 92, 84))
    put(px, x0 + 6, y0 + 5, (168, 92, 84))
    tx, ty, tw, td = box.top()
    for y in range(ty, ty + td):
        for x in range(tx, tx + tw):
            if rng.random() < 0.25:
                put(px, x, y, (170, 126, 102))


def detail_jacket_front(px, box, rng):
    x0, y0, w, h = box.front()
    # Open leather jacket over a striped telnyashka, a gold chain.
    for y in range(y0, y0 + h - 3):
        for x in range(x0 + 2, x0 + 6):
            put(px, x, y, (230, 230, 232) if (y - y0) % 2 == 0 else (40, 56, 110))
    for x in range(x0 + 2, x0 + 6):
        put(px, x, y0 + 1, (214, 176, 70) if x % 2 else (170, 132, 48))
    for x in range(x0, x0 + w):
        put(px, x, y0 + h - 3, (24, 20, 18))
        put(px, x, y0 + h - 2, (120, 104, 60) if x == x0 + w // 2 else (30, 26, 22))
    put(px, x0 + 1, y0, (70, 58, 50))
    put(px, x0 + w - 2, y0, (70, 58, 50))


def detail_fist(px, box, rng):
    for rect in (box.front(), box.back(), box.side_min_x(), box.side_max_x()):
        x0, y0, w, h = rect
        for y in range(y0 + h - 3, y0 + h):
            for x in range(x0, x0 + w):
                put(px, x, y, (198, 150, 122) if rng.random() > 0.15 else (160, 112, 92))
    bx, by, bw, bd = box.bottom()
    for x in range(bx, bx + bw):
        for y in range(by, by + bd):
            put(px, x, y, (180, 134, 108))


DETAILS = {
    "belly": detail_belly, "pseudogiant_face": detail_pseudogiant_face, "teeth": detail_teeth, "claw": detail_claw,
    "controller_face": detail_controller_face, "torn": detail_torn, "hand": detail_hand, "boots": detail_boots,
    "borov_face": detail_borov_face, "jacket_front": detail_jacket_front, "fist": detail_fist,
}


def all_boxes(parts):
    for part in parts:
        yield from part.boxes
        yield from all_boxes(part.children)


def paint(parts, tw, th, seed):
    img = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
    px = img.load()
    rng = random.Random(seed)
    seen = set()
    for box in all_boxes(parts):
        if (box.u, box.v) in seen:  # mirrored copies share their texture
            continue
        seen.add((box.u, box.v))
        paint_box(px, box, rng)
    return img


# ---------------------------------------------------------------- preview

def rot_matrix(xr, yr, zr):
    cx, sx, cy, sy, cz, sz = math.cos(xr), math.sin(xr), math.cos(yr), math.sin(yr), math.cos(zr), math.sin(zr)
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return matmul(matmul(rz, ry), rx)


def matmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def apply(m, t, p):
    return tuple(sum(m[i][k] * p[k] for k in range(3)) + t[i] for i in range(3))


def faces(box):
    x0, y0, z0 = box.x, box.y, box.z
    x1, y1, z1 = x0 + box.w, y0 + box.h, z0 + box.d
    if box.mirror:
        x0, x1 = x1, x0
    v = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    u, vv, w, h, d = box.u, box.v, box.w, box.h, box.d
    f4, f5, f6, f7, f8, f9 = u, u + d, u + d + w, u + d + 2 * w, u + 2 * d + w, u + 2 * d + 2 * w
    f10, f11, f12 = vv, vv + d, vv + d + h
    polys = [
        ((v[5], v[4], v[0], v[1]), f5, f10, f6, f11),
        ((v[2], v[3], v[7], v[6]), f6, f11, f7, f10),
        ((v[0], v[4], v[7], v[3]), f4, f11, f5, f12),
        ((v[1], v[0], v[3], v[2]), f5, f11, f6, f12),
        ((v[5], v[1], v[2], v[6]), f6, f11, f8, f12),
        ((v[4], v[5], v[6], v[7]), f8, f11, f9, f12),
    ]
    out = []
    for verts, u1, v1, u2, v2 in polys:
        if box.mirror:
            verts = (verts[1], verts[0], verts[3], verts[2])
        out.append((verts, u1, v1, u2, v2))
    return out


def render(parts, tex, size=420, yaw=-35.0, pitch=18.0, scale=None):
    px = tex.load()
    quads = []

    def walk(part, m, t):
        pm = rot_matrix(*part.rot)
        nm = matmul(m, pm)
        nt = apply(m, t, part.pivot)
        for box in part.boxes:
            for verts, u1, v1, u2, v2 in faces(box):
                world = [apply(nm, nt, p) for p in verts]
                # corners in (a along u, b along v): [1]=(0,0), [0]=(1,0), [2]=(0,1), [3]=(1,1)
                c00, c10, c01, c11 = world[1], world[0], world[2], world[3]
                du, dv = u2 - u1, v2 - v1
                nu, nv = int(round(abs(du))), int(round(abs(dv)))
                for i in range(nu):
                    for j in range(nv):
                        a0, a1 = i / nu, (i + 1) / nu
                        b0, b1 = j / nv, (j + 1) / nv
                        tu = int(min(u1, u2) + i) if du > 0 else int(max(u1, u2) - 1 - i)
                        tv = int(min(v1, v2) + j) if dv > 0 else int(max(v1, v2) - 1 - j)
                        color = px[tu % tex.width, tv % tex.height]
                        if color[3] == 0:
                            continue

                        def lerp(a, b):
                            top = [c00[k] + (c10[k] - c00[k]) * a for k in range(3)]
                            bot = [c01[k] + (c11[k] - c01[k]) * a for k in range(3)]
                            return [top[k] + (bot[k] - top[k]) * b for k in range(3)]

                        quads.append(([lerp(a0, b0), lerp(a1, b0), lerp(a1, b1), lerp(a0, b1)], color))
        for child in part.children:
            walk(child, nm, nt)

    identity = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    for part in parts:
        walk(part, identity, (0, 0, 0))

    cyaw, syaw = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def view(p):
        # Model space: y down, the creature faces -z. Turn it towards the camera and tilt the view down.
        x, y, z = -p[0], -p[1], -p[2]
        x, z = x * cyaw - z * syaw, x * syaw + z * cyaw
        y, z = y * cp - z * sp, y * sp + z * cp
        return x, y, z

    projected = []
    for corners, color in quads:
        vs = [view(c) for c in corners]
        e1 = [vs[1][k] - vs[0][k] for k in range(3)]
        e2 = [vs[3][k] - vs[0][k] for k in range(3)]
        normal_z = e1[0] * e2[1] - e1[1] * e2[0]
        projected.append((sum(v[2] for v in vs) / 4, vs, color, normal_z))
    xs = [v[0] for _, vs, _, _ in projected for v in vs]
    ys = [v[1] for _, vs, _, _ in projected for v in vs]
    span = max(max(xs) - min(xs), max(ys) - min(ys))
    k = scale or (size * 0.8 / span)
    cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
    img = Image.new("RGB", (size, size), (34, 38, 36))
    draw = ImageDraw.Draw(img)
    for _, vs, color, _ in sorted(projected, key=lambda q: -q[0]):
        draw.polygon([(size / 2 + (v[0] - cx) * k, size / 2 - (v[1] - cy) * k) for v in vs], fill=color[:3])
    return img


MODELS = {
    "pseudogiant": (pseudogiant_model, 7031),
    "controller": (controller_model, 4417),
    "borov": (player_model, 1960),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=ASSETS)
    ap.add_argument("--preview", default="")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    previews = []
    for name, (factory, seed) in MODELS.items():
        parts, tw, th = factory()
        tex = paint(parts, tw, th, seed)
        tex.save(os.path.join(args.out, f"{name}.png"))
        print(f"wrote {name}.png ({tw}x{th})")
        if args.preview:
            previews.append([render(parts, tex, yaw=yaw) for yaw in (-35.0, 145.0)])
            tex.resize((tw * 3, th * 3), Image.NEAREST).save(os.path.join(args.preview, f"{name}-texture.png"))
    if args.preview:
        os.makedirs(args.preview, exist_ok=True)
        sheet = Image.new("RGB", (840, 420 * len(previews)), (34, 38, 36))
        for row, pair in enumerate(previews):
            for col, img in enumerate(pair):
                sheet.paste(img, (col * 420, row * 420))
        sheet.save(os.path.join(args.preview, "bosses.png"))
        print(f"wrote {os.path.join(args.preview, 'bosses.png')}")


if __name__ == "__main__":
    main()
