#!/usr/bin/env python3
"""Prints mod ids, key bindings and mixin configs of the given jars, to see what a mod adds without a client.

Usage: describe_mods.py <jar> ...
"""
import json
import os
import re
import sys
import zipfile

MOD_ID = re.compile(r'^\s*modId\s*=\s*"([^"]+)"', re.M)


def main():
    for path in sys.argv[1:]:
        print(f"## {os.path.basename(path)}")
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            if "META-INF/mods.toml" in names:
                toml = z.read("META-INF/mods.toml").decode("utf-8", "replace")
                print("mod ids:", ", ".join(MOD_ID.findall(toml)))
            for n in names:
                if n.endswith("lang/en_us.json"):
                    try:
                        lang = json.loads(z.read(n))
                    except ValueError:
                        continue
                    keys = {k: v for k, v in lang.items() if k.startswith("key.") or ".key." in k or "keybind" in k}
                    for k, v in sorted(keys.items()):
                        print(f"  {k} = {v}")
                elif n.endswith(".json") and "/" not in n and "mixins" in n:
                    print("mixin config:", n)
        print()


if __name__ == "__main__":
    main()
