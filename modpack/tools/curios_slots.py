#!/usr/bin/env python3
"""Prints what the given jars register for Curios: slot types, which entities get them, and which
items each slot accepts (curios item tags), plus the artifact-related lang lines of Anomaly Zone.
Shows where an accessory goes without starting a client.

Usage: curios_slots.py <jar> ...
"""
import json
import os
import re
import sys
import zipfile

SLOT = re.compile(r'^data/([^/]+)/curios/slots/(.+)\.json$')
ENTITY = re.compile(r'^data/([^/]+)/curios/entities/(.+)\.json$')
TAG = re.compile(r'^data/curios/tags/items/(.+)\.json$')
CLASS = re.compile(r'(?i)(curio|artifact|artefact)[^/]*\.class$')


def load(z, name):
    try:
        return json.loads(z.read(name))
    except ValueError:
        return z.read(name).decode("utf-8", "replace")


def main():
    for path in sys.argv[1:]:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            lines = []
            for n in names:
                m = SLOT.match(n) or ENTITY.match(n) or TAG.match(n)
                if m:
                    lines.append(f"  {n}: {json.dumps(load(z, n), ensure_ascii=False)}")
            classes = [n for n in names if CLASS.search(n)]
            lang = []
            for n in names:
                if re.search(r'assets/anomalyzone/lang/(en_us|ru_ru)\.json$', n):
                    data = load(z, n)
                    if isinstance(data, dict):
                        for k, v in sorted(data.items()):
                            if re.search(r'(?i)curio|slot|artifact|artefact|tooltip|desc', k):
                                lang.append(f"  {n.split('/')[-1]} {k} = {v}")
            if not (lines or classes or lang):
                continue
            print(f"## {os.path.basename(path)}")
            print("\n".join(lines))
            if classes:
                print("  classes:", " ".join(sorted(classes)[:80]))
            print("\n".join(lang[:300]))
            print()


if __name__ == "__main__":
    main()
