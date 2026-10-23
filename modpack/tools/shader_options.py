#!/usr/bin/env python3
"""Lists the options of shader packs (zip), to write their settings in pack.json without a client.

Prints every `#define NAME value // [allowed values]` option with the file it is in, the profiles from
shaders.properties, the English names of option values, and checks the settings from pack.json: unknown
options and values outside an option's list are reported (Iris ignores them).
Usage: shader_options.py <mods.lock.json>
"""
import json
import os
import re
import sys
import zipfile

DEFINE = re.compile(r'^\s*(//)?\s*#define\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s+([^/\s]+))?\s*(?://\s*(.*))?$')
CACHE = os.path.expanduser("~/.cache/zoneraids-pack")


def options(z):
    found = {}
    for n in z.namelist():
        if not n.endswith((".glsl", ".fsh", ".vsh", ".inc")):
            continue
        for line in z.read(n).decode("utf-8", "replace").splitlines():
            m = DEFINE.match(line)
            # Options are toggles (a define without a value, maybe commented out) or values with a [list].
            if m and (m.group(1) or m.group(3) is None or (m.group(4) and "[" in m.group(4))) \
                    and m.group(2) not in found:
                value = m.group(3) if m.group(3) is not None else ("false" if m.group(1) else "true")
                found[m.group(2)] = (value, (m.group(4) or "").strip(), n)
    return found


def main():
    lock = json.load(open(sys.argv[1], encoding="utf-8"))
    for s in lock.get("shaderpacks", []):
        print(f"## {s['file']}")
        with zipfile.ZipFile(os.path.join(CACHE, s["file"])) as z:
            props = next((n for n in z.namelist() if n.endswith("shaders/shaders.properties")), None)
            if props:
                for line in z.read(props).decode("utf-8", "replace").splitlines():
                    if line.startswith(("profile.", "screen")):
                        print(f"  {line}")
            lang = next((n for n in z.namelist() if n.lower().endswith("shaders/lang/en_us.lang")), None)
            if lang:
                for line in z.read(lang).decode("utf-8", "replace").splitlines():
                    if line.startswith(("value.", "option.")):
                        print(f"  lang: {line}")
            opts = options(z)
        for name, (value, comment, where) in sorted(opts.items()):
            print(f"  {name} = {value}   {comment}   ({where})")
        problems = []
        for k, v in s.get("settings", {}).items():
            if k not in opts:
                problems.append(f"{k}: no such option")
                continue
            allowed = re.search(r"\[([^\]]*)\]", opts[k][1])
            if allowed and str(v) not in allowed.group(1).split():
                problems.append(f"{k}={v}: not one of [{allowed.group(1)}]")
            elif not allowed and str(v) not in ("true", "false"):
                problems.append(f"{k}={v}: toggle takes true/false")
        print("settings problems:" if problems else "settings problems: none")
        for line in problems:
            print(f"  BAD {line}")
        print()


if __name__ == "__main__":
    main()
