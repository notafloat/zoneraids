#!/usr/bin/env python3
"""Lists the default key of every key binding the given jars register, and the keys two bindings share.

Language files only name a binding; its default key lives in the bytecode, as the int argument of
`new KeyMapping("key.name", ..., GLFW_KEY_X, "category")` (GLFW constants are inlined by javac). This walks
every method, and for each `new <...Key...>` up to its constructor call takes the first string (the binding
name), the int constants (the key code) and Forge KeyModifier / InputConstants.Type fields (Ctrl/Shift/Alt,
mouse). Bindings built some other way (from config, in Kotlin helpers) show up as "?".

Vanilla keys are added so clashes with them show too. Two bindings on one key are reported as a clash; whether
it matters depends on the conflict context (in game vs GUI), so the report is a list to read, not a test.

Usage: key_defaults.py <mods-dir-or-jar> ...
"""
import io
import os
import struct
import sys
import zipfile
from collections import defaultdict

# GLFW key codes -> names (the ones mods use; others print as numbers).
GLFW = {32: "Space", 39: "'", 44: ",", 45: "-", 46: ".", 47: "/", 59: ";", 61: "=", 91: "[", 92: "\\", 93: "]", 96: "`",
        256: "Esc", 257: "Enter", 258: "Tab", 259: "Backspace", 260: "Insert", 261: "Delete", 262: "Right", 263: "Left",
        264: "Down", 265: "Up", 266: "PgUp", 267: "PgDn", 268: "Home", 269: "End", 280: "CapsLock", 281: "ScrollLock",
        282: "NumLock", 283: "PrintScreen", 284: "Pause", 340: "LShift", 341: "LCtrl", 342: "LAlt", 344: "RShift",
        345: "RCtrl", 346: "RAlt"}
GLFW.update({48 + i: str(i) for i in range(10)})
GLFW.update({65 + i: chr(65 + i) for i in range(26)})
GLFW.update({289 + i: f"F{i + 1}" for i in range(25)})
GLFW.update({320 + i: f"Num{i}" for i in range(10)})
MOUSE = {0: "LMB", 1: "RMB", 2: "MMB", 3: "Mouse4", 4: "Mouse5"}

VANILLA = {
    "key.attack": "LMB", "key.use": "RMB", "key.pickItem": "MMB", "key.forward": "W", "key.left": "A", "key.back": "S",
    "key.right": "D", "key.jump": "Space", "key.sneak": "LShift", "key.sprint": "LCtrl", "key.drop": "Q",
    "key.inventory": "E", "key.chat": "T", "key.playerlist": "Tab", "key.command": "/", "key.socialInteractions": "P",
    "key.screenshot": "F2", "key.togglePerspective": "F5", "key.smoothCamera": "?", "key.fullscreen": "F11",
    "key.spectatorOutlines": "?", "key.swapOffhand": "F", "key.saveToolbarActivator": "C", "key.loadToolbarActivator": "X",
    "key.advancements": "L",
}
VANILLA.update({f"key.hotbar.{i}": str(i) for i in range(1, 10)})

OPLEN = {}
for op in list(range(0x00, 0x10)) + list(range(0x1a, 0x36)) + list(range(0x3b, 0x84)) + list(range(0x85, 0x99)) \
        + list(range(0xac, 0xb2)) + [0xbe, 0xbf, 0xc2, 0xc3]:
    OPLEN[op] = 1
for op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc):
    OPLEN[op] = 2
for op in (0x11, 0x13, 0x14, 0x84, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7) \
        + tuple(range(0x99, 0xa9)):
    OPLEN[op] = 3
OPLEN.update({0xc5: 4, 0xb9: 5, 0xba: 5, 0xc8: 5, 0xc9: 5})


def parse_pool(data):
    count = struct.unpack_from(">H", data, 8)[0]
    pos, pool, i = 10, [None] * count, 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            n = struct.unpack_from(">H", data, pos)[0]
            pool[i] = ("utf8", data[pos + 2:pos + 2 + n].decode("utf-8", "replace"))
            pos += 2 + n
        elif tag == 3:
            pool[i] = ("int", struct.unpack_from(">i", data, pos)[0])
            pos += 4
        elif tag == 4:
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[i] = (tag,) + struct.unpack_from(">HH", data, pos)
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"constant tag {tag}")
        i += 1
    return pool, pos


def code_blocks(data, pool, pos):
    """Yields the bytecode of every method."""
    pos += 6
    n_if = struct.unpack_from(">H", data, pos)[0]
    pos += 2 + 2 * n_if

    def utf(idx):
        e = pool[idx]
        return e[1] if e and e[0] == "utf8" else None

    for kind in ("fields", "methods"):
        n = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        for _ in range(n):
            n_attr = struct.unpack_from(">H", data, pos + 6)[0]
            pos += 8
            for _ in range(n_attr):
                name_idx, length = struct.unpack_from(">HI", data, pos)
                if kind == "methods" and utf(name_idx) == "Code":
                    code_len = struct.unpack_from(">I", data, pos + 10)[0]
                    yield data[pos + 14:pos + 14 + code_len]
                pos += 6 + length


def scan(code, pool):
    """Yields (name, key ints, modifiers, mouse) for each `new X...Key...` .. `X.<init>` in a method."""

    def utf(idx):
        e = pool[idx]
        return e[1] if e and e[0] == "utf8" else None

    def cls(idx):
        e = pool[idx]
        return utf(e[1]) if e and e[0] == 7 else None

    def member(idx):
        e = pool[idx]
        nat = pool[e[2]]
        return cls(e[1]), utf(nat[1])

    stack = []  # open `new` frames: [class, strings, ints, modifiers, mouse]
    pc = 0
    while pc < len(code):
        op = code[pc]
        if op == 0xaa:  # tableswitch
            p = (pc + 4) & ~3
            lo, hi = struct.unpack_from(">ii", code, p + 4)
            pc = p + 12 + 4 * (hi - lo + 1)
            continue
        if op == 0xab:  # lookupswitch
            p = (pc + 4) & ~3
            n = struct.unpack_from(">i", code, p + 4)[0]
            pc = p + 8 + 8 * n
            continue
        if op == 0xc4:  # wide
            pc += 6 if code[pc + 1] == 0x84 else 4
            continue
        val = None
        if 0x02 <= op <= 0x08:
            val = op - 3
        elif op == 0x10:
            val = struct.unpack_from(">b", code, pc + 1)[0]
        elif op == 0x11:
            val = struct.unpack_from(">h", code, pc + 1)[0]
        elif op in (0x12, 0x13):
            idx = code[pc + 1] if op == 0x12 else struct.unpack_from(">H", code, pc + 1)[0]
            e = pool[idx]
            if e and e[0] == "int":
                val = e[1]
            elif e and e[0] == 8 and stack:
                stack[-1][1].append(utf(e[1]))
        elif op == 0xbb:
            name = cls(struct.unpack_from(">H", code, pc + 1)[0]) or ""
            stack.append([name, [], [], [], False])
        elif op == 0xb2 and stack:
            owner, field = member(struct.unpack_from(">H", code, pc + 1)[0])
            if owner and owner.endswith("KeyModifier") and field in ("CONTROL", "SHIFT", "ALT"):
                stack[-1][3].append(field.capitalize().replace("Control", "Ctrl"))
            elif owner and owner.endswith("InputConstants$Type"):
                # KEYSYM or MOUSE; field names are SRG in production, so key_name tells them apart by the code.
                stack[-1][4] = True
        elif op == 0xb7 and stack:
            owner, name = member(struct.unpack_from(">H", code, pc + 1)[0])
            if name == "<init>" and owner == stack[-1][0]:
                frame = stack.pop()
                simple = frame[0].rsplit("/", 1)[-1]
                if "Key" in simple and frame[1]:
                    yield frame[1][0], frame[2], frame[3], frame[4]
        if val is not None and stack:
            stack[-1][2].append(val)
        pc += OPLEN.get(op, 1)


def key_name(ints, modifiers, mouse):
    # The key code is the last int before the category; small ints before it are enum ordinals or flags.
    # Keyboard codes start at 32; with an explicit input type and only a small int, it is a mouse button.
    code = None
    for v in ints:
        if v == -1 or v >= 32:
            code = v
    if code is None and mouse:
        small = [v for v in ints if 0 <= v <= 7]
        if small:
            return "+".join(modifiers + [MOUSE.get(small[-1], f"Mouse{small[-1] + 1}")])
    if code is None:
        return "?"
    name = "unbound" if code == -1 else GLFW.get(code, str(code))
    return "+".join(modifiers + [name])


def jars(paths):
    for p in paths:
        if os.path.isdir(p):
            yield from (os.path.join(p, f) for f in sorted(os.listdir(p)) if f.endswith(".jar"))
        else:
            yield p


def iter_zip(data, label):
    z = zipfile.ZipFile(io.BytesIO(data))
    yield label, z
    for n in z.namelist():
        if n.startswith("META-INF/jarjar/") and n.endswith(".jar"):
            yield from iter_zip(z.read(n), f"{label}!{os.path.basename(n)}")


def main():
    found = {}  # binding -> (key, jar)
    for path in jars(sys.argv[1:]):
        with open(path, "rb") as f:
            raw = f.read()
        for label, z in iter_zip(raw, os.path.basename(path)):
            for n in z.namelist():
                if not n.endswith(".class"):
                    continue
                data = z.read(n)
                if data[:4] != b"\xca\xfe\xba\xbe":
                    continue
                try:
                    pool, pos = parse_pool(data)
                    for code in code_blocks(data, pool, pos):
                        for name, ints, mods, mouse in scan(code, pool):
                            if "." in name and " " not in name:
                                found.setdefault(name, (key_name(ints, mods, mouse), label))
                except (ValueError, struct.error, IndexError, TypeError):
                    continue
    by_jar = defaultdict(list)
    for name, (key, label) in found.items():
        by_jar[label].append((name, key))
    for label in sorted(by_jar):
        print(f"## {label}")
        for name, key in sorted(by_jar[label]):
            print(f"  {key:<14} {name}")
        print()
    by_key = defaultdict(list)
    for name, key in VANILLA.items():
        by_key[key].append(name)
    for name, (key, _) in found.items():
        by_key[key].append(name)
    print("## Keys shared by more than one binding (vanilla included)")
    for key in sorted(by_key):
        if key not in ("?", "unbound") and len(by_key[key]) > 1:
            print(f"  {key:<14} {', '.join(sorted(by_key[key]))}")


if __name__ == "__main__":
    main()
