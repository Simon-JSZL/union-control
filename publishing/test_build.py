"""Regression check for moved, uncommitted source files in release archives."""
import contextlib
import io
import shutil
import subprocess
import tempfile
from pathlib import Path
import unittest
import zipfile

import build


class BuildTest(unittest.TestCase):
    def test_working_tree_moves_and_ignored_files(self):
        original = build.ROOT
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("pom.xml", "README.md", "PROJECT_OVERVIEW.md", "LICENSE",
                         "package.json", "publishing"):
                source = original / name
                if source.is_dir():
                    shutil.copytree(source, root / name)
                else:
                    shutil.copy(source, root / name)
            for name in ("ark-control", "ark-control-facade", "ark-web"):
                (root / name).mkdir()
                shutil.copy(original / name / "pom.xml", root / name / "pom.xml")
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            (root / ".gitignore").write_text(".env*\ntarget/\n")
            old = root / "ark-control/OldTest.java"
            old.write_text("old")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                            "commit", "-qm", "fixture"], cwd=root, check=True)
            old.unlink()
            new = root / "ark-control/import-test/NewTest.java"
            new.parent.mkdir()
            new.write_text("updated test")
            included = ["ark-control/src/main/resources/schema.sql",
                        "ark-control/src/main/resources/application.properties",
                        "docs/integration.md", "download.zip"]
            for name in included:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("include this file")
            # Ignore rules apply even to a previously tracked file.
            with (root / ".gitignore").open("a") as rules:
                rules.write("README.md\n.DS_Store\n")
            (new.parent / ".env.local").write_text("do not distribute")
            (new.parent / ".DS_Store").write_text("do not distribute")
            try:
                build.ROOT = root
                with contextlib.redirect_stdout(io.StringIO()):
                    build.build()
            finally:
                build.ROOT = original
            with zipfile.ZipFile(next((root / "target").rglob("*-source.zip"))) as archive:
                entries = {name.split("/", 1)[1] for name in archive.namelist()}
                self.assertIn("ark-control/import-test/NewTest.java", entries)
                self.assertNotIn("ark-control/OldTest.java", entries)
                self.assertTrue(set(included).issubset(entries))
                self.assertNotIn("README.md", entries)
                self.assertFalse(any(".env" in name or ".DS_Store" in name for name in entries))


if __name__ == "__main__":
    unittest.main()
