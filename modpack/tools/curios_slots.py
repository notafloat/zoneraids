#!/usr/bin/env python3
"""Prints what the given jars register for Curios: slot types, which entities get them, and which
items each slot accepts (curios item tags), plus the artifact-related lang lines of Anomaly Zone and
the screens its overlay and container classes refer to.
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


def screen_refs(data):
    """Screen and GUI event classes named in a class file's constant pool: which screens a mod draws on."""
    names = set(re.findall(rb'net/minecraft[\w/$]*(?:Screen|Gui)[\w$]*|net/minecraftforge/client/event/[\w$]+', data))
    return sorted(n.decode() for n in names)


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
                            if re.search(r'(?i)curio|slot|artifact|artefact|tooltip|desc|container|ksm|berloga|hive|legend_zone|money|price|ruble|trader', k):
                                lang.append(f"  {n.split('/')[-1]} {k} = {v}")
            if not (lines or classes or lang):
                continue
            print(f"## {os.path.basename(path)}")
            print("\n".join(lines))
            if classes:
                print("  classes:", " ".join(sorted(classes)[:80]))
            for n in sorted(classes):
                if re.search(r'(?i)overlay|container', n):
                    refs = screen_refs(z.read(n))
                    if refs:
                        print(f"  {n} uses: {' '.join(refs)}")
            print("\n".join(lang[:300]))
            print()


if __name__ == "__main__":
    main()
