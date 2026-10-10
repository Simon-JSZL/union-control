#!/usr/bin/env python3
"""Build the source ZIP and unsigned Central layout; no network or credentials."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def build():
    pom = ET.parse(ROOT / "publishing/pom.xml").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    group, artifact, version = [pom.findtext("m:" + field, namespaces=ns)
                                for field in ("groupId", "artifactId", "version")]
    if version != json.loads((ROOT / "package.json").read_text())["version"]:
        raise ValueError("Update publishing/pom.xml and package.json to the same version")
    base = f"{artifact}-{version}"
    output = ROOT / "target" / "maven-central" / version
    layout = output / "repository" / group.replace(".", "/") / artifact / version
    layout.mkdir(parents=True, exist_ok=True)
    candidates = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT).decode().split("\0")
    modules = {"ark-control-facade", "ark-control", "ark-web"}
    extra = {"pom.xml", "README.md", "PROJECT_OVERVIEW.md", "LICENSE",
             "publishing/README.md",
             "publishing/pom.xml", "publishing/build.py", "publishing/test_build.py", "package.json"}
    selected = set(extra)
    for name in candidates:
        path = Path(name)
        if not name or path.parts[0] not in modules:
            continue
        if (path.name == ".DS_Store" or "target" in path.parts or "/src/test/" in name
                or path.name in {"application.properties", "application.yml", "schema.sql"}):
            continue
        if (ROOT / path).is_symlink():
            raise ValueError(f"Refusing symlink: {name}")
        if not (ROOT / path).is_file():
            continue
        selected.add(name)
    archive = layout / f"{base}-source.zip"
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip()
    manifest = {}
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(selected):
            data = (ROOT / name).read_bytes()
            manifest[name] = hashlib.sha256(data).hexdigest()
            info = zipfile.ZipInfo(f"{base}/{name}", (2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            z.writestr(info, data)
        z.writestr(f"{base}/SOURCE-MANIFEST.json", json.dumps({
            "baseCommit": revision, "snapshot": "working-tree", "sha256": manifest
        }, indent=2) + "\n")
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        for module in modules:
            assert f"{base}/{module}/pom.xml" in z.namelist()
        for name, digest in manifest.items():
            assert hashlib.sha256(z.read(f"{base}/{name}")).hexdigest() == digest
    published_pom = layout / f"{base}.pom"
    published_pom.write_bytes((ROOT / "publishing/pom.xml").read_bytes())
    for path in (archive, published_pom):
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            path.with_name(path.name + "." + algorithm).write_text(
                hashlib.new(algorithm, path.read_bytes()).hexdigest() + "\n")
    print(json.dumps({"archive": str(archive), "layout": str(layout),
                      "files": len(selected), "baseCommit": revision}, indent=2))


if __name__ == "__main__":
    build()
