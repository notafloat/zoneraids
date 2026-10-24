#!/usr/bin/env python3
"""Builds the Zone Raids modpack from modpack/pack.json.

Outputs (in --out):
  ZoneRaids-<version>.mrpack        client pack for Prism Launcher / Modrinth App / ATLauncher
  ZoneRaids-<version>-server.zip    ready-to-run Forge server (mods, configs, start scripts)
  (with --variant: <file_prefix>-<version>... from modpack/variants/<name>/variant.json)
  mods.lock.json                    exact files that went into this build (mods and shader packs)
  server/                           unpacked server (used by the smoke test)

Needs only the Python standard library and network access to Modrinth, Forge maven and forgecdn.
"""
import argparse
import hashlib
import json
import os
import shutil
import sys
import time
import urllib.parse
import urllib.request
import zipfile

MODRINTH = "https://api.modrinth.com/v2"
FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge"
USER_AGENT = "notafloat/zoneraids-modpack-builder (github.com/notafloat/zoneraids)"

HERE = os.path.dirname(os.path.abspath(__file__))
PACK_DIR = os.path.dirname(HERE)


def http_get(url, retries=4):
    delay = 2
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:  # network hiccups and Modrinth rate limits
            if attempt == retries:
                raise RuntimeError(f"GET {url} failed: {e}") from e
            time.sleep(delay)
            delay *= 2


def get_json(url):
    return json.loads(http_get(url))


def modrinth_versions(project, mc):
    q = urllib.parse.urlencode({"loaders": json.dumps(["forge"]), "game_versions": json.dumps([mc])})
    return get_json(f"{MODRINTH}/project/{project}/version?{q}")


def pick_version(versions, wanted):
    if wanted:
        # Authors format version numbers freely ("2.0" vs file "Anomaly_Zone-2.0.0-..."), so also match file names.
        for match in (lambda v: v["version_number"] == wanted,
                      lambda v: any(wanted in f["filename"] for f in v["files"])):
            hits = [v for v in versions if match(v)]
            if hits:
                return max(hits, key=lambda v: v["date_published"])
        print(f"WARNING: pinned version {wanted} not found, using newest; available: "
              f"{[v['version_number'] for v in versions[:10]]}", flush=True)
    releases = [v for v in versions if v["version_type"] == "release"] or versions
    if not releases:
        return None
    return max(releases, key=lambda v: v["date_published"])


def primary_file(version):
    files = version["files"]
    for f in files:
        if f.get("primary"):
            return f
    jars = [f for f in files if f["filename"].endswith(".jar") and "sources" not in f["filename"]]
    return (jars or files)[0]


def side_env(side):
    return {
        "both": {"client": "required", "server": "required"},
        "client": {"client": "required", "server": "unsupported"},
        "server": {"client": "unsupported", "server": "required"},
    }[side]


def resolve(pack):
    """Resolves pinned/newest Modrinth versions plus their required dependencies."""
    mc = pack["minecraft"]
    resolved = {}  # project_id -> entry
    pending = []
    entries = [dict(m, _extra=False) for m in pack["mods"]] + [dict(m, _extra=True) for m in pack.get("server_extras", [])]

    for m in entries:
        if "url" in m:
            resolved["url:" + m["file"]] = {
                "name": m["name"], "file": m["file"], "url": m["url"], "side": m["side"],
                "source": "url", "server_only_extra": m["_extra"],
            }
            continue
        pending.append((m["slug"], m.get("version"), m.get("side"), m["_extra"], None))

    while pending:
        slug, wanted, side, extra, parent = pending.pop(0)
        project = get_json(f"{MODRINTH}/project/{slug}")
        pid = project["id"]
        if side is None or (parent and project["server_side"] == "unsupported"):
            side = "client" if project["server_side"] == "unsupported" else (
                "server" if project["client_side"] == "unsupported" else "both")
        if pid in resolved:
            # A library first pulled in by a client-only mod may also be needed by a server-side mod.
            if resolved[pid]["side"] != side:
                resolved[pid]["side"] = "both"
            resolved[pid]["server_only_extra"] = resolved[pid]["server_only_extra"] and extra
            continue
        version = pick_version(modrinth_versions(pid, mc), wanted)
        if version is None:
            raise SystemExit(f"{project['slug']} (needed by {parent or 'pack.json'}) has no Forge {mc} version")
        f = primary_file(version)
        resolved[pid] = {
            "name": project["title"], "slug": project["slug"], "project_id": pid,
            "version": version["version_number"], "version_id": version["id"],
            "file": f["filename"], "url": f["url"], "sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"],
            "size": f["size"], "side": side, "source": "modrinth", "license": project["license"]["id"],
            "server_only_extra": extra, "required_by": parent,
        }
        for dep in version["dependencies"]:
            if dep["dependency_type"] != "required" or not dep.get("project_id"):
                continue
            pending.append((dep["project_id"], None, side, extra, project["slug"]))
        print(f"  {project['slug']:32} {version['version_number']:30} {side}", flush=True)

    return list(resolved.values())


def resolve_shaderpacks(pack):
    """Shader packs are Modrinth projects for the "iris" loader; they go to shaderpacks/ on the client only."""
    mc = pack["minecraft"]
    out = []
    for s in pack.get("shaderpacks", []):
        project = get_json(f"{MODRINTH}/project/{s['slug']}")
        q = urllib.parse.urlencode({"loaders": json.dumps(["iris"]), "game_versions": json.dumps([mc])})
        versions = get_json(f"{MODRINTH}/project/{project['id']}/version?{q}")
        if not versions:  # some packs do not tag every game version
            q = urllib.parse.urlencode({"loaders": json.dumps(["iris"])})
            versions = get_json(f"{MODRINTH}/project/{project['id']}/version?{q}")
        version = pick_version(versions, s.get("version"))
        if version is None:
            raise SystemExit(f"shader pack {s['slug']} has no Iris version")
        f = primary_file(version)
        out.append({
            "name": project["title"], "slug": project["slug"], "project_id": project["id"],
            "version": version["version_number"], "version_id": version["id"],
            "file": f["filename"], "url": f["url"], "sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"],
            "size": f["size"], "side": "client", "source": "modrinth", "license": project["license"]["id"],
            "default": s.get("default", False), "settings": s.get("settings", {}),
        })
        print(f"  {project['slug']:32} {version['version_number']:30} shaderpack", flush=True)
    return out


def oculus_files(shaderpacks):
    """Oculus config that turns the default pack on, plus each pack's settings file (shaderpacks/<zip>.txt)."""
    files = {}
    default = next((s for s in shaderpacks if s["default"]), None)
    if default:
        files["config/oculus.properties"] = (
            "# Written by the Zone Raids pack build. Shaders: O (or F8 if O is taken), toggle F7.\n"
            "enableShaders=true\n"
            f"shaderPack={default['file']}\n"
            "disableUpdateMessage=true\n"
            "maxShadowRenderDistance=4\n")
    for s in shaderpacks:
        if s["settings"]:
            files[f"shaderpacks/{s['file']}.txt"] = "".join(f"{k}={v}\n" for k, v in s["settings"].items())
    return files


def resolve_forge(pack):
    if pack["forge"] != "recommended":
        return pack["forge"]
    promos = get_json(FORGE_PROMOS)["promos"]
    mc = pack["minecraft"]
    return promos.get(f"{mc}-recommended") or promos[f"{mc}-latest"]


def download(entry, cache):
    os.makedirs(cache, exist_ok=True)
    path = os.path.join(cache, entry["file"])
    if not os.path.exists(path):
        data = http_get(entry["url"])
        if "sha512" in entry and hashlib.sha512(data).hexdigest() != entry["sha512"]:
            raise SystemExit(f"hash mismatch for {entry['file']}")
        with open(path, "wb") as fh:
            fh.write(data)
    if "sha512" not in entry:
        with open(path, "rb") as fh:
            data = fh.read()
        entry["sha1"] = hashlib.sha1(data).hexdigest()
        entry["sha512"] = hashlib.sha512(data).hexdigest()
        entry["size"] = len(data)
    return path


def build_glue_jar(version, dest):
    glue = os.path.join(PACK_DIR, "glue")
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(glue):
            for name in sorted(files):
                full = os.path.join(root, name)
                rel = os.path.relpath(full, glue).replace(os.sep, "/")
                if rel == "META-INF/mods.toml":
                    with open(full, encoding="utf-8") as fh:
                        z.writestr(rel, fh.read().replace("${pack_version}", version))
                else:
                    z.write(full, rel)


def copy_tree(src, dst):
    if os.path.isdir(src):
        shutil.copytree(src, dst, dirs_exist_ok=True)


def layered(dirs):
    """Files of several folders by relative path; a later folder's file replaces an earlier one's."""
    files = {}
    for src in dirs:
        for root, _, names in os.walk(src):
            for name in sorted(names):
                full = os.path.join(root, name)
                files[os.path.relpath(full, src).replace(os.sep, "/")] = full
    return files


def add_dirs_to_zip(z, dirs, prefix):
    for rel, full in sorted(layered(dirs).items()):
        z.write(full, f"{prefix}{rel}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zoneraids-jar", required=True, help="built Zone Raids mod jar")
    ap.add_argument("--out", default="build/modpack")
    ap.add_argument("--cache", default=os.path.expanduser("~/.cache/zoneraids-pack"))
    ap.add_argument("--variant", default="", help="modpack/variants/<name>: same mods, its configs on top of the pack's")
    args = ap.parse_args()

    with open(os.path.join(PACK_DIR, "pack.json"), encoding="utf-8") as fh:
        pack = json.load(fh)
    prefix = "ZoneRaids"
    variant_dir = None
    if args.variant:
        variant_dir = os.path.join(PACK_DIR, "variants", args.variant)
        with open(os.path.join(variant_dir, "variant.json"), encoding="utf-8") as fh:
            variant = json.load(fh)
        pack["name"] = variant.get("name", pack["name"])
        pack["summary"] = variant.get("summary", pack["summary"])
        pack["mods"] = pack["mods"] + variant.get("mods", [])
        prefix = variant["file_prefix"]
        print(f"Variant {args.variant}: {pack['name']}", flush=True)
    version = pack["version"]
    out = os.path.abspath(args.out)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)

    print("Resolving mods:", flush=True)
    mods = resolve(pack)
    shaderpacks = resolve_shaderpacks(pack)
    forge = resolve_forge(pack)
    print(f"Forge {pack['minecraft']}-{forge}", flush=True)

    for m in mods + shaderpacks:
        download(m, args.cache)

    glue_name = f"zoneraids_pack-{version}.jar"
    glue_path = os.path.join(out, glue_name)
    build_glue_jar(version, glue_path)
    zr_name = os.path.basename(args.zoneraids_jar)

    lock = {"pack": version, "minecraft": pack["minecraft"], "forge": forge, "mods": mods,
            "shaderpacks": shaderpacks, "local": [zr_name, glue_name]}
    with open(os.path.join(out, "mods.lock.json"), "w", encoding="utf-8") as fh:
        json.dump(lock, fh, indent=2, ensure_ascii=False)

    layers = [PACK_DIR] + ([variant_dir] if variant_dir else [])
    common = [os.path.join(d, "overrides") for d in layers]
    client_only = [os.path.join(d, "client-overrides") for d in layers]
    server_only = [os.path.join(d, "server-overrides") for d in layers]

    # Client .mrpack: Modrinth files by URL, everything else (our jars, forgecdn jars, configs) as overrides.
    index = {
        "formatVersion": 1, "game": "minecraft", "versionId": version, "name": pack["name"],
        "summary": pack["summary"], "files": [],
        "dependencies": {"minecraft": pack["minecraft"], "forge": forge},
    }
    url_jars = []
    for m in mods:
        if m.get("server_only_extra"):
            continue
        if m["source"] == "modrinth":
            index["files"].append({
                "path": f"mods/{m['file']}", "hashes": {"sha1": m["sha1"], "sha512": m["sha512"]},
                "env": side_env(m["side"]), "downloads": [m["url"]], "fileSize": m["size"],
            })
        else:
            url_jars.append(m)
    for s in shaderpacks:
        index["files"].append({
            "path": f"shaderpacks/{s['file']}", "hashes": {"sha1": s["sha1"], "sha512": s["sha512"]},
            "env": side_env("client"), "downloads": [s["url"]], "fileSize": s["size"],
        })
    mrpack = os.path.join(out, f"{prefix}-{version}.mrpack")
    with zipfile.ZipFile(mrpack, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, indent=2, ensure_ascii=False))
        z.write(args.zoneraids_jar, f"overrides/mods/{zr_name}")
        z.write(glue_path, f"overrides/mods/{glue_name}")
        for m in url_jars:
            if m["side"] in ("both", "client"):
                z.write(os.path.join(args.cache, m["file"]), f"overrides/mods/{m['file']}")
        add_dirs_to_zip(z, common, "overrides/")
        add_dirs_to_zip(z, client_only, "client-overrides/")
        for path, text in oculus_files(shaderpacks).items():
            z.writestr(f"client-overrides/{path}", text)

    # Server: unpacked folder plus zip of it.
    server = os.path.join(out, "server")
    os.makedirs(os.path.join(server, "mods"))
    for m in mods:
        if m["side"] in ("both", "server"):
            shutil.copy2(os.path.join(args.cache, m["file"]), os.path.join(server, "mods", m["file"]))
    shutil.copy2(args.zoneraids_jar, os.path.join(server, "mods", zr_name))
    shutil.copy2(glue_path, os.path.join(server, "mods", glue_name))
    for src in common + server_only:
        copy_tree(src, server)
    with open(os.path.join(server, "forge-version.txt"), "w") as fh:
        fh.write(f"{pack['minecraft']}-{forge}\n")
    for script in ("start-linux.sh",):
        p = os.path.join(server, script)
        if os.path.exists(p):
            os.chmod(p, 0o755)
    server_zip = os.path.join(out, f"{prefix}-{version}-server.zip")
    with zipfile.ZipFile(server_zip, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(server):
            for name in sorted(files):
                full = os.path.join(root, name)
                rel = os.path.relpath(full, server).replace(os.sep, "/")
                info = zipfile.ZipInfo.from_file(full, f"{prefix}-server/{rel}")
                with open(full, "rb") as fh:
                    z.writestr(info, fh.read(), zipfile.ZIP_DEFLATED)

    client_count = sum(1 for m in mods if m["side"] in ("both", "client") and not m.get("server_only_extra")) + 2
    server_count = len(os.listdir(os.path.join(server, "mods")))
    print(f"Client mods: {client_count}, server mods: {server_count}")
    print(f"Wrote {mrpack}\nWrote {server_zip}")


if __name__ == "__main__":
    sys.exit(main())
