import hashlib
import importlib
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from zipfile import ZipFile, ZipInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main/python"))
from plugin_dependencies import (DependencyError, DependencyPlan, Wheel,
                                 prepare_requirements, install_requirements,
                                 validate_required_plugins)


def wheel(name="demo", version="1.0", dependencies=(), files=None, python=">=3.8", tag="py3-none-any"):
    stream = io.BytesIO()
    with ZipFile(stream, "w") as archive:
        prefix = f"{name}-{version}.dist-info/"
        metadata = f"Metadata-Version: 2.1\nName: {name}\nVersion: {version}\nRequires-Python: {python}\n"
        metadata += "".join(f"Requires-Dist: {d}\n" for d in dependencies)
        archive.writestr(prefix + "METADATA", metadata)
        archive.writestr(prefix + "WHEEL", f"Wheel-Version: 1.0\nRoot-Is-Purelib: true\nTag: {tag}\n")
        for path, value in (files or {name + "/__init__.py": "VALUE = 42\n"}).items():
            archive.writestr(path, value)
    return Wheel(f"{name}-{version}-{tag}.whl", stream.getvalue())


class Index:
    def __init__(self, *wheels):
        self.responses = {}
        packages = {}
        for artifact in wheels:
            name, version = artifact.filename.split("-")[:2]
            url = "https://files.pythonhosted.org/" + artifact.filename
            self.responses[url] = artifact.data
            packages.setdefault(name, {}).setdefault(version, []).append({
                "filename": artifact.filename, "url": url, "packagetype": "bdist_wheel",
                "digests": {"sha256": hashlib.sha256(artifact.data).hexdigest()},
            })
        for name, releases in packages.items():
            self.responses[f"https://pypi.org/pypi/{name.replace('_', '-')}/json"] = json.dumps({"releases": releases}).encode()

    def __call__(self, url):
        return self.responses[url]


class DependenciesTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("PLUGIN_DEPENDENCIES_LIVE") == "1", "opt-in real PyPI test")
    def test_live_pypi_install(self):
        plan = prepare_requirements(["requests==2.32.3", "tinydb==4.8.2"])
        self.assertGreaterEqual(len(plan.wheels), 6)
        with tempfile.TemporaryDirectory() as root:
            paths = install_requirements(plan, Path(root) / "site")
            sys.path[:0] = paths
            try:
                import requests
                import tinydb
                self.assertEqual(requests.__version__, "2.32.3")
                self.assertTrue(Path(requests.__file__).is_relative_to(root))
                self.assertTrue(Path(tinydb.__file__).is_relative_to(root))
                from tinydb.storages import MemoryStorage
                database = tinydb.TinyDB(storage=MemoryStorage)
                database.insert({"installed": True})
                self.assertEqual(database.all(), [{"installed": True}])
            finally:
                for path in paths:
                    sys.path.remove(path)
                for name, module in list(sys.modules.items()):
                    filename = getattr(module, "__file__", None)
                    if filename and Path(filename).is_relative_to(root):
                        sys.modules.pop(name, None)

    def test_real_extraction_and_import_with_transitive_dependency(self):
        index = Index(wheel("utility_dep"), wheel("utility_app", dependencies=["utility_dep>=1"],
                      files={"utility_app/__init__.py": "from utility_dep import VALUE\n"}))
        plan = prepare_requirements(["utility_app==1.0"], fetch=index)
        with tempfile.TemporaryDirectory() as root:
            paths = install_requirements(plan, Path(root) / "site")
            sys.path[:0] = paths
            try:
                self.assertEqual(importlib.import_module("utility_app").VALUE, 42)
            finally:
                for path in paths:
                    sys.path.remove(path)
                for name in ("utility_dep", "utility_app"):
                    sys.modules.pop(name, None)

    def test_backtracking_and_comma_ranges(self):
        index = Index(wheel("demo", "2.0", ["dep>=2"]), wheel("demo", "1.0", ["dep<2"]),
                      wheel("dep", "1.0"), wheel("dep", "2.0"))
        plan = prepare_requirements("demo>=1,<3, dep<2", fetch=index)
        self.assertEqual([w.filename for w in plan.wheels], ["demo-1.0-py3-none-any.whl", "dep-1.0-py3-none-any.whl"])

    def test_extras_and_android_markers(self):
        index = Index(wheel("demo", dependencies=['dep; extra == "feature"', 'missing; sys_platform == "win32"']), wheel("dep"))
        plan = prepare_requirements(["demo[feature]"], fetch=index)
        self.assertEqual(len(plan.wheels), 2)

    def test_comma_list_preserves_extras_ranges_and_quoted_markers(self):
        index = Index(wheel("demo"), wheel("dep"), wheel("third"))
        for requirements in (
                "demo,dep,third",
                "demo, dep, third",
                'demo[a, b, c]>=1,<2; os_name not in "other, unknown", dep, third'):
            with self.subTest(requirements=requirements):
                plan = prepare_requirements(requirements, fetch=index)
                self.assertEqual([w.filename for w in plan.wheels], [
                    "demo-1.0-py3-none-any.whl", "dep-1.0-py3-none-any.whl",
                    "third-1.0-py3-none-any.whl"])

    def test_conflicting_versions(self):
        with self.assertRaisesRegex(DependencyError, "No compatible"):
            prepare_requirements(["demo<1", "demo>=2"], fetch=Index(wheel()))

    def test_incompatible_python_and_binary_tags(self):
        for artifact in (wheel(python=">=99"), wheel(tag="cp311-cp311-manylinux_2_17_x86_64")):
            with self.subTest(artifact=artifact.filename), self.assertRaisesRegex(DependencyError, "No compatible"):
                prepare_requirements(["demo"], fetch=Index(artifact))

    def test_hash_verification(self):
        index = Index(wheel())
        index.responses["https://files.pythonhosted.org/demo-1.0-py3-none-any.whl"] += b"tampered"
        with self.assertRaisesRegex(DependencyError, "SHA256"):
            prepare_requirements(["demo"], fetch=index)

    def test_unsafe_archives_leave_no_target(self):
        cases = [("../escape.py", "bad"), ("/absolute.py", "bad"), ("a\\b.py", "bad"),
                 ("C:/bad.py", "bad"), ("pkg/native.so", b"x"), ("pkg/hidden", b"\x7fELFxxx"),
                 ("startup.pth", "import bad"), ("demo-1.0.data/scripts/run", "bad")]
        link = ZipInfo("pkg/link")
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        cases.append((link, "../../outside"))
        for name, content in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as root:
                target = Path(root) / "site"
                with self.assertRaises(DependencyError):
                    install_requirements(DependencyPlan((wheel(files={name: content}),)), target)
                self.assertFalse(target.exists())
                self.assertEqual(list(Path(root).iterdir()), [])

    def test_bundled_wheel_and_data_relocation(self):
        artifact = wheel(files={"demo-1.0.data/purelib/demo/__init__.py": "VALUE=7"})
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / artifact.filename
            path.write_bytes(artifact.data)
            plan = prepare_requirements(wheels=[path], fetch=lambda url: self.fail(url))
            target = Path(root) / "site"
            install_requirements(plan, target)
            self.assertTrue((target / "demo/__init__.py").is_file())
            with self.assertRaisesRegex(DependencyError, "new directory"):
                install_requirements(plan, target)

    def test_collision_is_atomic(self):
        plan = DependencyPlan((wheel(), wheel("other", files={"demo/__init__.py": "collision"})))
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(DependencyError, "collision"):
                install_requirements(plan, Path(root) / "site")
            self.assertEqual(list(Path(root).iterdir()), [])

    def test_metadata_version_and_tag_validation(self):
        for metadata in ("Wheel-Version: 2.0\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
                         "Wheel-Version: 1.0\nRoot-Is-Purelib: false\nTag: py3-none-any\n",
                         "Wheel-Version: 1.0\nRoot-Is-Purelib: true\nTag: cp311-cp311-android_24_arm64_v8a\n"):
            stream = io.BytesIO()
            original = wheel()
            with ZipFile(io.BytesIO(original.data)) as source, ZipFile(stream, "w") as output:
                for entry in source.infolist():
                    output.writestr(entry, metadata if entry.filename.endswith("/WHEEL") else source.read(entry))
            with tempfile.TemporaryDirectory() as root, self.assertRaises(DependencyError):
                install_requirements(DependencyPlan((Wheel(original.filename, stream.getvalue()),)), Path(root) / "site")

    def test_unsupported_requirement_forms(self):
        for value in (["demo @ https://example.org/demo.whl"], ["--index-url=x"], {"demo": "1"}, ["demo===nonsense"]):
            with self.subTest(value=value), self.assertRaises(DependencyError):
                prepare_requirements(value, fetch=Index(wheel()))

    def test_required_plugins_are_not_python_packages(self):
        requires = {"helper (2.0)": "https://example.org/helper.elyx"}
        validate_required_plugins(requires, {"helper": {"enabled": True, "version": "2.1"}})
        for state in ({}, {"helper": {"enabled": False, "version": "2.1"}}, {"helper": {"enabled": True, "version": "1.0"}}):
            with self.assertRaises(DependencyError):
                validate_required_plugins(requires, state)


if __name__ == "__main__":
    unittest.main()
