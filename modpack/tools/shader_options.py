#!/usr/bin/env python3
"""Lists the options of shader packs (zip), to write their settings in pack.json without a client.

Prints every `#define NAME value // [allowed values]` option with the file it is in, the profiles from
shaders.properties, and the settings from pack.json that the pack does not have.
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
            # Iris shows a define as an option only when it has a [values] list or is a commented-out toggle.
            if m and (m.group(1) or (m.group(4) and "[" in m.group(4))) and m.group(2) not in found:
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
                        print(f"  {line[:400]}")
            opts = options(z)
        for name, (value, comment, where) in sorted(opts.items()):
            print(f"  {name} = {value}   {comment[:120]}   ({where})")
        missing = [k for k in s.get("settings", {}) if k not in opts]
        print(f"settings not in the pack: {missing or 'none'}")
        print()


if __name__ == "__main__":
    main()
