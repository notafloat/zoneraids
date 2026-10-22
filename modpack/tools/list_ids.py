#!/usr/bin/env python3
"""Lists item ids (from item models) and TaCZ gun/ammo/attachment ids found in a mods folder.

Used to write loot tables without guessing ids. Usage: list_ids.py <mods-dir> [<extra-dir> ...]
"""
import os
import re
import sys
import zipfile

ITEM_MODEL = re.compile(r"^assets/([a-z0-9_.-]+)/models/item/([a-z0-9_./-]+)\.json$")
# TaCZ gun packs: .../data/<ns>/index/{guns,ammo,attachments}/<id>.json
TACZ_INDEX = re.compile(r"(?:^|/)data/([a-z0-9_.-]+)/index/(guns|ammo|attachments)/([a-z0-9_./-]+)\.json$")


def scan_names(names, items, tacz):
    for n in names:
        m = ITEM_MODEL.match(n)
        if m:
            items.setdefault(m.group(1), set()).add(m.group(2))
            continue
        m = TACZ_INDEX.search(n)
        if m:
            tacz.setdefault(m.group(2), set()).add(f"{m.group(1)}:{m.group(3)}")


def main():
    items, tacz = {}, {}
    for root_dir in sys.argv[1:]:
        for root, _, files in os.walk(root_dir):
            for f in files:
                path = os.path.join(root, f)
                if f.endswith(".jar") or f.endswith(".zip"):
                    try:
                        with zipfile.ZipFile(path) as z:
                            scan_names(z.namelist(), items, tacz)
                    except zipfile.BadZipFile:
                        pass
                else:
                    scan_names([os.path.relpath(path, root_dir).replace(os.sep, "/")], items, tacz)
    for kind in ("guns", "ammo", "attachments"):
        print(f"## TaCZ {kind}")
        print(" ".join(sorted(tacz.get(kind, ()))) or "(none)")
    print()
    for ns in sorted(items):
        if ns == "minecraft":
            continue
        print(f"## {ns} ({len(items[ns])} items)")
        print(" ".join(f"{ns}:{i}" for i in sorted(items[ns])))


if __name__ == "__main__":
    main()
