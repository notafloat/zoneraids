#!/usr/bin/env python3
"""Checks that mods calling into other mods of the pack still match those mods' code.

Addons such as TACtical Movement are compiled against one version of TaCZ; if TaCZ renamed a class, method
or field since then, the addon crashes only when that code runs (often only on the client, which the server
smoke test never starts). This script reads the bytecode of every jar (and jars nested in META-INF/jarjar)
and reports references into another mod's packages that do not exist there:

  * classes, methods and fields used by the code (constant pool),
  * class names in annotations and strings, e.g. @Mixin(SomeTaczClass.class) or @Mixin(targets = "..."),
  * mixin refmap targets (the methods mixins inject into).

References into packages no jar provides (Minecraft, Forge, optional mods that are not installed) are skipped.

Usage: check_mod_links.py <mods-dir-or-jar> ... [--strict <jar-name-substring> ...] [--report <file>]
Exits 1 if a jar matching --strict has problems; problems of other jars are printed as warnings.
"""
import argparse
import io
import json
import os
import re
import struct
import sys
import zipfile

DESC_CLASS = re.compile(r"^L([A-Za-z_$][\w$]*(?:/[A-Za-z_$][\w$]*)+);$")
DOTTED_CLASS = re.compile(r"^([a-z_][\w]*(?:\.[a-z_][\w]*)+)\.([A-Z][\w$]*)$")
REFMAP_MEMBER = re.compile(r"^L([\w/$]+);([\w$<>]+)(?::|(?=\())(.*)$")


class ClassInfo:
    __slots__ = ("name", "super", "interfaces", "methods", "fields")

    def __init__(self, name, sup, interfaces, methods, fields):
        self.name, self.super, self.interfaces, self.methods, self.fields = name, sup, interfaces, methods, fields


def parse_class(data):
    """Returns (ClassInfo, refs, strings). refs: (kind, owner, name, desc); strings: all Utf8 constants."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    pos = 8
    count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    pool = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            n = struct.unpack_from(">H", data, pos)[0]
            pool[i] = ("utf8", data[pos + 2:pos + 2 + n].decode("utf-8", "replace"))
            pos += 2 + n
        elif tag in (3, 4):
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
            return None
        i += 1

    def utf(idx):
        e = pool[idx]
        return e[1] if e and e[0] == "utf8" else None

    def cls(idx):
        e = pool[idx]
        return utf(e[1]) if e and e[0] == 7 else None

    access, this_idx, super_idx, n_if = struct.unpack_from(">HHHH", data, pos)
    pos += 8
    interfaces = [cls(struct.unpack_from(">H", data, pos + 2 * k)[0]) for k in range(n_if)]
    pos += 2 * n_if

    def members(pos):
        n = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        out = set()
        for _ in range(n):
            _, name_idx, desc_idx, n_attr = struct.unpack_from(">HHHH", data, pos)
            pos += 8
            for _ in range(n_attr):
                pos += 6 + struct.unpack_from(">I", data, pos + 2)[0]
            out.add((utf(name_idx), utf(desc_idx)))
        return out, pos

    fields, pos = members(pos)
    methods, pos = members(pos)
    info = ClassInfo(cls(this_idx), cls(super_idx) if super_idx else None, interfaces, methods, fields)

    refs, strings = [], []
    for e in pool:
        if not e:
            continue
        if e[0] == "utf8":
            strings.append(e[1])
        elif e[0] == 7:
            refs.append(("class", utf(e[1]), None, None))
        elif e[0] in (9, 10, 11):
            nat = pool[e[2]]
            kind = "field" if e[0] == 9 else "method"
            refs.append((kind, cls(e[1]), utf(nat[1]), utf(nat[2])))
    return info, refs, strings


def iter_jar(data, label):
    """Yields (label, zipfile) for a jar and the jars nested in it."""
    z = zipfile.ZipFile(io.BytesIO(data))
    yield label, z
    for n in z.namelist():
        if n.startswith("META-INF/jarjar/") and n.endswith(".jar"):
            yield from iter_jar(z.read(n), f"{label}!{os.path.basename(n)}")


class Jar:
    def __init__(self, label):
        self.label = label
        self.classes = {}
        self.refs = []      # (class, kind, owner, name, desc)
        self.strings = []   # (class, string)
        self.refmaps = []   # (file, string)
        self.mixin_configs = []


def jar_paths(paths):
    for p in paths:
        if os.path.isdir(p):
            yield from (os.path.join(p, f) for f in sorted(os.listdir(p)) if f.endswith(".jar"))
        else:
            yield p


def load(paths):
    jars = []
    for path in jar_paths(paths):
        with open(path, "rb") as fh:
            data = fh.read()
        for label, z in iter_jar(data, os.path.basename(path)):
            jar = Jar(label)
            for n in z.namelist():
                if n.endswith(".class") and not n.startswith("META-INF/versions/"):
                    parsed = parse_class(z.read(n))
                    if not parsed or not parsed[0].name:
                        continue
                    info, refs, strings = parsed
                    jar.classes[info.name] = info
                    jar.refs.extend((info.name,) + r for r in refs)
                    jar.strings.extend((info.name, s) for s in strings)
                elif n.endswith(".json") and "/" not in n:
                    try:
                        doc = json.loads(z.read(n))
                    except ValueError:
                        continue
                    if isinstance(doc, dict) and "mappings" in doc:
                        for table in doc["mappings"].values():
                            jar.refmaps.extend((n, v) for v in table.values() if isinstance(v, str))
                    elif isinstance(doc, dict) and ("mixins" in doc or "client" in doc) and "package" in doc:
                        jar.mixin_configs.append(n)
            jars.append(jar)
    return jars


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+", help="mods folders and/or jar files")
    ap.add_argument("--strict", nargs="*", default=[])
    ap.add_argument("--report")
    args = ap.parse_args()

    jars = load(args.paths)
    owner_of = {}      # class -> jar
    package_of = {}    # package -> set of jar labels
    for jar in jars:
        for name in jar.classes:
            owner_of.setdefault(name, jar)
            package_of.setdefault(name.rpartition("/")[0], set()).add(jar.label)

    def find_member(owner, name, desc, kind, seen=None):
        """True/False if decidable, None if the hierarchy leaves the pack (e.g. a Minecraft superclass)."""
        seen = seen if seen is not None else set()
        if owner in seen:
            return False
        seen.add(owner)
        jar = owner_of.get(owner)
        if jar is None:
            return None
        info = jar.classes[owner]
        table = info.methods if kind == "method" else info.fields
        if (name, desc) in table or (desc is None and any(n == name for n, _ in table)):
            return True
        undecided = False
        for parent in [info.super] + info.interfaces:
            if not parent:
                continue
            if parent == "java/lang/Object":
                if kind == "method" and name in ("<init>", "getClass", "hashCode", "equals", "toString", "clone",
                                                 "notify", "notifyAll", "wait", "finalize"):
                    return True
                continue
            r = find_member(parent, name, desc, kind, seen)
            if r:
                return True
            if r is None:
                undecided = True
        return None if undecided else False

    def foreign(jar, owner):
        """The other jars that own this package, if the reference crosses into another mod."""
        if not owner or owner.startswith("["):
            return None
        holders = package_of.get(owner.rpartition("/")[0], set())
        if not holders or jar.label in holders:
            return None
        return holders

    problems, unchecked = {}, {}
    for jar in jars:
        found = set()

        def problem(msg):
            found.add(msg)

        for src, kind, owner, name, desc in jar.refs:
            if not foreign(jar, owner):
                continue
            if owner not in owner_of:
                problem(f"missing class {owner} (used in {src})")
            elif kind != "class":
                r = find_member(owner, name, desc, kind)
                if r is False:
                    problem(f"missing {kind} {owner}.{name}{desc if kind == 'method' else ' : ' + desc} (used in {src})")
                elif r is None:
                    unchecked[jar.label] = unchecked.get(jar.label, 0) + 1
        for src, s in jar.strings:
            m = DESC_CLASS.match(s)
            name = m.group(1) if m else None
            if not name:
                m = DOTTED_CLASS.match(s)
                name = (m.group(1).replace(".", "/") + "/" + m.group(2)) if m else None
            if name and foreign(jar, name) and name not in owner_of:
                problem(f"missing class {name} (named in {src})")
        for refmap, s in jar.refmaps:
            m = REFMAP_MEMBER.match(s)
            owner = m.group(1) if m else (s if "/" in s and not s.startswith("L") else None)
            if not owner or not foreign(jar, owner):
                continue
            if owner not in owner_of:
                problem(f"missing mixin target class {owner} ({refmap})")
            elif m:
                desc = m.group(3) or None
                kind = "method" if desc and desc.startswith("(") else "field"
                if find_member(owner, m.group(2), desc, kind) is False:
                    problem(f"missing mixin target {owner}.{m.group(2)}{desc or ''} ({refmap})")
        if found:
            problems[jar.label] = sorted(found)

    out = []
    links = {}
    for jar in jars:
        targets = set()
        for _, kind, owner, _, _ in jar.refs:
            holders = foreign(jar, owner)
            if holders:
                targets.update(holders)
        if targets:
            links[jar.label] = sorted(targets)
    out.append("== Cross-mod links (jar -> jars it calls into; unchecked = members inherited from Minecraft/Java)")
    for label, targets in sorted(links.items()):
        out.append(f"{label} -> {', '.join(targets)} (unchecked: {unchecked.get(label, 0)})")
    out.append("")
    strict_fail = False
    for label, msgs in sorted(problems.items()):
        strict = any(s in label for s in args.strict)
        strict_fail |= strict
        out.append(f"== {'ERROR' if strict else 'WARNING'}: {label}: {len(msgs)} unresolved references")
        out.extend("  " + m for m in msgs[:200])
        if len(msgs) > 200:
            out.append(f"  ... {len(msgs) - 200} more")
    if not problems:
        out.append("All cross-mod references resolve.")
    text = "\n".join(out)
    print(text)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
    return 1 if strict_fail else 0


if __name__ == "__main__":
    sys.exit(main())
