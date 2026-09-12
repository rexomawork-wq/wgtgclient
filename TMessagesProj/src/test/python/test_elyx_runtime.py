"""Real engine load/unload, resources and persistence; no Android needed."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main/python"))
import elyx
import wgtg_plugin_engine as engine
from base_plugin import AppEvent


SOURCE = '''
from base_plugin import BasePlugin, HookResult
from elyx import settings, strings, assets, metainfo, get_environment, import_module
helper = import_module("helper")
settings["imports"] = settings("imports", 0) + 1
class Plugin(BasePlugin):
    def on_plugin_load(self):
        self.label = strings("hello", name="Alice")
        self.asset = assets.sample.content_json()
        self.description = metainfo["description"]
        self.helper = helper.VALUE
        self.add_hook("Request")
    def pre_request_hook(self, name, account, request):
        settings["hook_id"] = get_environment()["plugin_id"]
        return HookResult()
    def on_app_event(self, event):
        settings["event_id"] = get_environment()["plugin_id"]
    def on_plugin_unload(self):
        settings["unloaded"] = get_environment()["plugin_id"]
'''


class ElyxRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        engine.initialize(str(self.root))
        self.addCleanup(engine.shutdown)

    def plugin(self, plugin_id="elyx_test", ref=None, source=SOURCE):
        folder = self.root / plugin_id
        (folder / "src").mkdir(parents=True)
        (folder / "res").mkdir()
        (folder / "locales").mkdir()
        (folder / "src/main.py").write_text(source)
        (folder / "src/helper.py").write_text("VALUE = 'local'\n")
        (folder / "res/sample.json").write_text('{"value":42}')
        (folder / "locales/strings_en.yml").write_text('hello: "Hello {name}"\ndescription: Localized\n')
        (folder / "locales/other_en.py").write_text('from elyx import settings\nsettings["locale_loaded"] = True\nfrom pathlib import Path\nextra = "python"\ndef ignored(): pass\n')
        (folder / "meta.json").write_text(json.dumps({"id": plugin_id, "name": "Elyx test", "description": "{description} {missing}"}))
        (folder / "refmap.json").write_text(json.dumps(ref or {"main": "src/main.py", "metainfo": "meta.json", "assets": "res", "strings": "locales", "custom": 42}))
        return folder

    def test_load_resources_callbacks_persistence_reload_and_cleanup(self):
        self.plugin()
        engine.set_enabled("elyx_test", True)
        item = engine.loaded["elyx_test"]
        self.assertEqual(item["error"], "")
        instance = item["instance"]
        self.assertEqual((instance.label, instance.asset, instance.description, instance.helper),
                         ("Hello Alice", {"value": 42}, "Localized missing", "local"))
        env = item["environment"]
        self.assertEqual(env["refmap"]["custom"], 42)
        self.assertEqual(env["strings"].get_with_locale("extra"), "python")
        self.assertEqual(env["strings"].get_with_locale("ignored"), "ignored")
        self.assertTrue(engine.get_setting("elyx_test", "locale_loaded"))
        self.assertEqual(json.loads(engine.list_plugins())[0]["description"], "Localized missing")
        engine.before_request("Request", 0, object())
        engine.on_app_event(AppEvent.RESUME)
        self.assertEqual(engine.get_setting("elyx_test", "hook_id"), "elyx_test")
        self.assertEqual(engine.get_setting("elyx_test", "event_id"), "elyx_test")
        # Explicit controller also works for shared-library code passed an id.
        self.assertEqual(elyx.SettingsController("elyx_test").get("imports"), 1)
        engine.set_enabled("elyx_test", False)
        self.assertEqual(engine.get_setting("elyx_test", "unloaded"), "elyx_test")
        self.assertNotIn("wgtg_plugin_elyx_test", elyx._environments)
        self.assertFalse(any(n.startswith("wgtg_plugin_elyx_test") for n in sys.modules))
        engine.set_enabled("elyx_test", True)
        self.assertEqual(engine.get_setting("elyx_test", "imports"), 2)
        elyx.SettingsController("elyx_test").clear_settings()
        self.assertEqual(engine.get_settings("elyx_test"), {})

    def test_failure_during_localization_or_entry_cleans_environment(self):
        folder = self.plugin()
        for target in (folder / "locales/other_en.py", folder / "src/main.py"):
            with self.subTest(target=target):
                original = target.read_text()
                target.write_text("raise ValueError('load failure')")
                with patch("hook_utils.unhook_all"):
                    engine.set_enabled("elyx_test", True)
                self.assertIn("load failure", engine.loaded["elyx_test"]["error"])
                self.assertNotIn("wgtg_plugin_elyx_test", elyx._environments)
                self.assertFalse(any(n.startswith("wgtg_plugin_elyx_test") for n in sys.modules))
                target.write_text(original)

    def test_two_plugins_and_nested_dispatch_restore_scope(self):
        self.plugin("elyx_one")
        self.plugin("elyx_two")
        engine.set_enabled("elyx_one", True)
        engine.set_enabled("elyx_two", True)
        first = engine.loaded["elyx_one"]["environment"]
        with elyx.environment_scope(first):
            engine.before_request("Request", 0, None)
            self.assertIs(elyx.get_environment(), first)
        self.assertEqual(engine.get_setting("elyx_one", "hook_id"), "elyx_one")
        self.assertEqual(engine.get_setting("elyx_two", "hook_id"), "elyx_two")

    def test_resource_escape_and_missing_declared_directory_fail_closed(self):
        folder = self.plugin()
        for path in ("../outside", "missing", "/tmp", "res/../../outside"):
            with self.subTest(path=path):
                (folder / "refmap.json").write_text(json.dumps({"main": "src/main.py", "metainfo": "meta.json", "assets": path}))
                engine.set_enabled("elyx_test", True)
                self.assertIsNone(engine.loaded["elyx_test"]["instance"])
                self.assertNotIn("wgtg_plugin_elyx_test", elyx._environments)

    def test_default_assets_single_catalog_and_yaml_refmap_precedence(self):
        folder = self.plugin()
        (folder / "res").rename(folder / "assets")
        (folder / "refmap.yaml").write_text('main: src/main.py\nmetainfo: meta.json\nstrings: locales/strings_en.yml\n')
        engine.set_enabled("elyx_test", True)
        self.assertEqual(engine.loaded["elyx_test"]["error"], "")
        self.assertEqual(engine.loaded["elyx_test"]["instance"].asset, {"value": 42})

    def test_python_metadata_is_read_without_execution(self):
        folder = self.plugin()
        (folder / "meta.py").write_text('id = "elyx_test"\n__name__ = "Python metadata"\ndescription = "{description}"\nraise AssertionError("metadata must not execute")\n')
        ref = json.loads((folder / "refmap.json").read_text())
        ref["metainfo"] = "meta.py"
        (folder / "refmap.json").write_text(json.dumps(ref))
        engine.set_enabled("elyx_test", True)
        self.assertEqual(engine.loaded["elyx_test"]["error"], "")
        self.assertEqual(engine.loaded["elyx_test"]["meta"]["name"], "Python metadata")

    def test_localization_executes_after_bundled_dependency_activation(self):
        from test_plugin_dependencies import wheel
        folder = self.plugin()
        artifact = wheel("elyx_locale_dep")
        (folder / "wheels").mkdir()
        (folder / "wheels" / artifact.filename).write_bytes(artifact.data)
        ref = json.loads((folder / "refmap.json").read_text())
        ref["wheels"] = "wheels"
        (folder / "refmap.json").write_text(json.dumps(ref))
        (folder / "locales/other_en.py").write_text('from elyx_locale_dep import VALUE\nextra = VALUE\n')
        engine.set_enabled("elyx_test", True)
        self.assertEqual(engine.loaded["elyx_test"]["error"], "")
        self.assertEqual(engine.loaded["elyx_test"]["environment"]["strings"].get_with_locale("extra"), 42)
        self.assertIn("elyx_locale_dep", sys.modules)
        engine.set_enabled("elyx_test", False)
        self.assertNotIn("elyx_locale_dep", sys.modules)


if __name__ == "__main__":
    unittest.main()
