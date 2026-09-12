"""Dependency runtime integration, using real wheels and an offline PyPI fixture."""
import hashlib
import io
import json
import pathlib
import sys
import tempfile
import threading
import unittest
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python"))
import plugin_dependencies as dependencies
import wgtg_plugin_engine as engine


def wheel(version="1.0", module="wgtg_test_library", distribution="wgtgtestlib"):
    output = io.BytesIO()
    info = f"{distribution}-{version}.dist-info"
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr(module + "/__init__.py", f"VERSION = {version!r}\n")
        archive.writestr(module + "/lazy.py", "VALUE = 'lazy import works'\n")
        archive.writestr(info + "/METADATA", f"Metadata-Version: 2.1\nName: {distribution}\nVersion: {version}\n")
        archive.writestr(info + "/WHEEL", "Wheel-Version: 1.0\nRoot-Is-Purelib: true\nTag: py3-none-any\n")
    return f"{distribution}-{version}-py3-none-any.whl", output.getvalue()


class DependencyRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        engine.initialize(str(self.root / "plugins"))
        self.addCleanup(engine.shutdown)
        self.network = patch.object(dependencies, "_fetch", side_effect=AssertionError("unexpected network"))
        self.fetch = self.network.start()
        self.addCleanup(self.network.stop)

    def source(self, plugin_id="library_user", requirements=None, requires=None, body=None):
        body = body or "import wgtg_test_library\nself.version = wgtg_test_library.VERSION"
        return (f'from base_plugin import BasePlugin\n__id__ = {plugin_id!r}\n__name__ = "Library User"\n'
                f'__requirements__ = {requirements!r}\n__requires__ = {requires!r}\n'
                'class Plugin(BasePlugin):\n def on_plugin_load(self):\n  ' + body.replace("\n", "\n  ") + "\n")

    def archive(self, plugin_id="library_user", version="1.0", module="wgtg_test_library", requires=None):
        path = self.root / (plugin_id + ".plugin")
        filename, data = wheel(version, module)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("main.py", self.source(plugin_id, requires=requires))
            archive.writestr("refmap.json", json.dumps({"main": "main.py", "wheels": "wheels"}))
            archive.writestr("wheels/" + filename, data)
        return str(path)

    def remote(self, requirements="wgtgtestlib==1.0", plugin_id="library_user"):
        path = self.root / (plugin_id + ".plugin")
        path.write_text(self.source(plugin_id, requirements))
        return str(path)

    def index(self, version="1.0"):
        filename, data = wheel(version)
        index = {"releases": {version: [{"filename": filename, "packagetype": "bdist_wheel",
                 "url": "https://files.example/" + filename, "digests": {"sha256": hashlib.sha256(data).hexdigest()}}]}}
        def fetch(url):
            if url == "https://pypi.org/pypi/wgtgtestlib/json": return json.dumps(index).encode()
            if url == "https://files.example/" + filename: return data
            raise AssertionError(url)
        self.fetch.side_effect = fetch

    def generation(self, pid="library_user"):
        return pathlib.Path(engine.root) / engine._state()["dependencies"][pid]["path"]

    def test_remote_install_then_offline_enable_lazy_import_and_restart(self):
        self.index()
        source = pathlib.Path(self.remote())
        source.write_text(source.read_text().replace("class Plugin", "import wgtg_test_library\nclass Plugin"))
        pid = engine.install(str(source))
        self.assertNotIn("wgtg_test_library", sys.modules)
        generation = self.generation(pid)
        self.assertTrue(generation.is_dir())
        self.assertNotIn(str(generation), sys.path)
        self.fetch.side_effect = AssertionError("must use persisted dependencies")
        engine.set_enabled(pid, True)
        self.assertEqual(engine.loaded[pid]["instance"].version, "1.0")
        import wgtg_test_library.lazy
        self.assertEqual(wgtg_test_library.lazy.VALUE, "lazy import works")
        engine.initialize(engine.root)
        self.assertEqual(engine.loaded[pid]["instance"].version, "1.0")
        self.assertEqual(sys.path.count(str(generation)), 1)
        engine.set_enabled(pid, False)
        self.assertNotIn("wgtg_test_library", sys.modules)
        self.assertNotIn("wgtg_test_library.lazy", sys.modules)
        self.assertNotIn(str(generation), sys.path)
        engine.uninstall(pid)
        self.assertFalse(generation.exists())

    def test_bundled_wheel_update_replaces_generation_and_code(self):
        pid = engine.install(self.archive())
        engine.set_enabled(pid, True)
        old = self.generation(pid)
        engine.install(self.archive(version="2.0"))
        self.assertFalse(old.exists())
        self.assertNotIn("wgtg_test_library", sys.modules)
        engine.set_enabled(pid, True)
        self.assertEqual(engine.loaded[pid]["instance"].version, "2.0")
        self.fetch.assert_not_called()

    def test_failed_download_preserves_running_plugin_and_generation(self):
        pid = engine.install(self.archive())
        engine.set_enabled(pid, True)
        instance = engine.loaded[pid]["instance"]
        generation = self.generation(pid)
        self.fetch.side_effect = OSError("network down")
        with self.assertRaisesRegex(OSError, "network down"):
            engine.install(self.remote("wgtgtestlib==2.0"))
        self.assertIs(engine.loaded[pid]["instance"], instance)
        self.assertEqual(self.generation(pid), generation)
        self.assertTrue(generation.exists())
        self.assertTrue(engine._state()["enabled"][pid])

    def test_state_failure_rolls_back_files_and_removes_new_generation(self):
        pid = engine.install(self.archive())
        engine.set_enabled(pid, True)
        generation = self.generation(pid)
        instance = engine.loaded[pid]["instance"]
        write = engine._write_json
        def fail_state(path, value):
            if path == engine._state_path(): raise OSError("disk full")
            return write(path, value)
        with patch.object(engine, "_write_json", side_effect=fail_state):
            with self.assertRaisesRegex(OSError, "disk full"):
                engine.install(self.archive(version="2.0"))
        self.assertEqual(list(generation.parent.iterdir()), [generation])
        self.assertIs(engine.loaded[pid]["instance"], instance)
        self.assertEqual(self.generation(pid), generation)

    def test_interrupted_update_restores_old_files_and_collects_orphan_generation(self):
        import os
        pid = engine.install(self.archive())
        engine.set_enabled(pid, True)
        old_generation = self.generation(pid)
        engine.shutdown()
        stage = tempfile.mkdtemp(prefix=".install-", dir=engine.root)
        _, meta = engine._prepare(self.archive(version="2.0"), stage)
        abandoned = engine._prepare_dependencies(pid, stage, meta)
        engine._write_json(str(pathlib.Path(stage) / ".wgtg-install.json"), "uncommitted")
        os.replace(pathlib.Path(engine.root) / pid, pathlib.Path(engine.root) / (".backup-" + pid))
        os.replace(stage, pathlib.Path(engine.root) / pid)
        engine.initialize(engine.root)
        self.assertEqual(engine.loaded[pid]["instance"].version, "1.0")
        self.assertEqual(self.generation(pid), old_generation)
        self.assertFalse((pathlib.Path(engine.root) / abandoned["path"]).exists())
        self.assertFalse((pathlib.Path(engine.root) / (".backup-" + pid)).exists())

    def test_nested_entrypoint_and_yaml_metadata_wheels(self):
        filename, data = wheel()
        path = self.root / "nested.elyx"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("src/main.py", self.source())
            archive.writestr("refmap.yml", "main: src/main.py\nmetainfo: metainfo.yml\nwheels: wheels\n")
            archive.writestr("metainfo.yml", "id: library_user\nname: Nested\nrequirements: wgtgtestlib==1.0\n")
            archive.writestr("wheels/" + filename, data)
        meta = json.loads(engine.inspect_plugin(str(path)))
        self.assertEqual(meta["requirements"], "wgtgtestlib==1.0")
        pid = engine.install(str(path))
        engine.set_enabled(pid, True)
        self.assertEqual(engine.loaded[pid]["instance"].version, "1.0")
        engine.initialize(engine.root)
        self.assertEqual(engine.loaded[pid]["instance"].version, "1.0")
        self.fetch.assert_not_called()

    def test_preview_does_not_resolve_download_execute_or_persist(self):
        path = self.remote(["wgtgtestlib>=1.0"], "preview_plugin")
        with open(path, "a") as source: source.write("\nraise RuntimeError('must not execute')\n")
        with patch.object(dependencies, "prepare_requirements", side_effect=AssertionError("must not resolve")):
            meta = json.loads(engine.inspect_plugin(path))
            self.assertEqual(meta["requirements"], ["wgtgtestlib>=1.0"])
            meta = json.loads(engine.inspect_plugin(self.archive()))
            self.assertEqual(meta["wheels"], ["wgtgtestlib-1.0-py3-none-any.whl"])
        self.fetch.assert_not_called()
        self.assertEqual(list(pathlib.Path(engine.root).iterdir()), [])

    def test_conflicting_active_plugin_and_host_library_are_rejected(self):
        pid = engine.install(self.archive())
        engine.set_enabled(pid, True)
        with self.assertRaisesRegex(ValueError, "active plugin library_user"):
            engine.install(self.archive("another_user", "2.0"))
        with self.assertRaisesRegex(ValueError, "host library"):
            engine.install(self.archive("host_override", module="packaging"))
        self.assertEqual(engine.loaded[pid]["instance"].version, "1.0")
        engine.set_enabled(pid, False)
        other = engine.install(self.archive("another_user", "2.0"))
        engine.set_enabled(other, True)
        engine.set_enabled(pid, True)
        self.assertIsNone(engine.loaded[pid]["instance"])
        self.assertIn("active plugin another_user", engine.loaded[pid]["error"])
        self.assertEqual(engine.loaded[other]["instance"].version, "2.0")

    def test_legacy_enable_prepares_before_import(self):
        self.index()
        (pathlib.Path(engine.root) / "legacy_user.py").write_text(self.source("legacy_user", "wgtgtestlib==1.0"))
        engine.set_enabled("legacy_user", True)
        self.assertEqual(engine.loaded["legacy_user"]["instance"].version, "1.0")
        self.assertTrue(self.generation("legacy_user").exists())

    def test_failed_load_deactivates_but_preserves_installed_generation(self):
        path = self.remote()
        pathlib.Path(path).write_text(self.source(requirements="wgtgtestlib==1.0", body="import wgtg_test_library\nraise RuntimeError('broken plugin')"))
        self.index()
        pid = engine.install(path)
        engine.set_enabled(pid, True)
        self.assertIn("broken plugin", engine.loaded[pid]["error"])
        self.assertNotIn("wgtg_test_library", sys.modules)
        self.assertNotIn(str(self.generation(pid)), sys.path)
        self.assertTrue(self.generation(pid).exists())

    def test_required_plugins_validate_minimum_and_restart_order(self):
        provider = self.root / "provider.plugin"
        provider.write_text(self.source("provider", body="pass") + '\n__version__ = "2.1"\n')
        consumer = self.root / "consumer.plugin"
        consumer.write_text(self.source("consumer", requires={"provider (2.0)": "https://example/provider"}, body="pass"))
        with self.assertRaisesRegex(ValueError, "missing or disabled"):
            engine.install(str(consumer))
        engine.install(str(provider))
        engine.set_enabled("provider", True)
        engine.install(str(consumer))
        engine.set_enabled("consumer", True)
        for operation in (lambda: engine.set_enabled("provider", False), lambda: engine.uninstall("provider"), lambda: engine.install(str(provider))):
            with self.assertRaisesRegex(ValueError, "Disable dependent plugin consumer"):
                operation()
        descriptors = engine._descriptors()
        with patch.object(engine, "_descriptors", return_value=sorted(descriptors)):
            engine.initialize(engine.root)
        self.assertIsNotNone(engine.loaded["consumer"]["instance"])
        self.assertIsNotNone(engine.loaded["provider"]["instance"])
        consumer.write_text(self.source("new_consumer", requires={"provider (3.0)": "https://example/provider"}, body="pass"))
        with self.assertRaisesRegex(ValueError, ">= 3.0"):
            engine.install(str(consumer))

    def test_restart_missing_generation_does_not_download(self):
        pid = engine.install(self.archive())
        engine.set_enabled(pid, True)
        engine.shutdown()
        import shutil
        shutil.rmtree(self.generation(pid))
        engine.initialize(engine.root)
        self.assertIsNone(engine.loaded[pid]["instance"])
        self.assertIn("reinstall", engine.loaded[pid]["error"])
        self.fetch.assert_not_called()

    def test_ignored_marker_is_persisted_without_repeated_resolution(self):
        path = self.root / "ignored.plugin"
        path.write_text(self.source("ignored", 'wgtgtestlib; sys_platform == "win32"', body="pass"))
        pid = engine.install(str(path))
        with patch.object(dependencies, "prepare_requirements", side_effect=AssertionError("re-resolved")):
            engine.set_enabled(pid, True)
            engine.initialize(engine.root)
        self.assertIsNotNone(engine.loaded[pid]["instance"])

    def test_download_does_not_hold_hook_dispatch_lock(self):
        self.index()
        fetch = self.fetch.side_effect
        def checked_fetch(url):
            finished = threading.Event()
            worker = threading.Thread(target=lambda: (engine.before_request("Probe", 0, object()), finished.set()), daemon=True)
            worker.start()
            self.assertTrue(finished.wait(2), "dependency download blocks hook dispatch")
            worker.join()
            return fetch(url)
        self.fetch.side_effect = checked_fetch
        engine.install(self.remote())

    def test_corrupt_state_does_not_delete_plugin_or_generation(self):
        pid = engine.install(self.archive())
        generation = self.generation(pid)
        state = pathlib.Path(engine._state_path())
        saved = state.read_text()
        state.write_text("{broken")
        try:
            with self.assertRaisesRegex(ValueError, "state is corrupt"):
                engine.initialize(engine.root)
            self.assertTrue(generation.is_dir())
            self.assertTrue((pathlib.Path(engine.root) / pid / "main.py").is_file())
        finally:
            state.write_text(saved)

    def test_enable_state_failure_removes_unpublished_generation(self):
        self.index()
        (pathlib.Path(engine.root) / "legacy_user.py").write_text(self.source("legacy_user", "wgtgtestlib==1.0"))
        with patch.object(engine, "_write_json", side_effect=OSError("state failure")):
            with self.assertRaisesRegex(OSError, "state failure"):
                engine.set_enabled("legacy_user", True)
        self.assertEqual(list((pathlib.Path(engine.root) / ".dependencies").iterdir()), [])
        self.assertNotIn("legacy_user", engine.loaded)
        self.assertNotIn("wgtg_test_library", sys.modules)


if __name__ == "__main__":
    unittest.main()
