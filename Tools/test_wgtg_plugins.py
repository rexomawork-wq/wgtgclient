import json
import pathlib
import sys
import tempfile
import unittest
import zipfile
from types import SimpleNamespace

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python"))
import wgtg_plugin_engine as engine

SOURCE = '''from base_plugin import BasePlugin, HookResult, HookStrategy
from ui.settings import Input
__id__ = "hello_world"
__name__ = "Hello"
class Hello(BasePlugin):
    def on_plugin_load(self):
        self.add_on_send_message_hook()
        self.add_hook("Typing")
    def create_settings(self):
        return [Input(key="greeting", text="Greeting", default="Hello")]
    def on_send_message_hook(self, account, params):
        params.message = self.get_setting("greeting", "Hello") + " " + params.message
        return HookResult(strategy=HookStrategy.MODIFY, params=params)
    def pre_request_hook(self, name, account, request):
        return HookResult(strategy=HookStrategy.CANCEL)
'''

class PluginTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        for pid in list(engine.loaded): engine._unload(pid)
        engine.initialize(str(pathlib.Path(self.tmp.name) / "plugins"))

    def install(self, source=SOURCE):
        path = pathlib.Path(self.tmp.name) / "input.plugin"
        path.write_text(source)
        return engine.install(str(path))

    def test_lifecycle_hooks_and_settings(self):
        pid = self.install()
        self.assertFalse(json.loads(engine.list_plugins())[0]["enabled"])
        engine.set_enabled(pid, True)
        value = SimpleNamespace(message="Alice")
        self.assertIs(engine.before_send_message(2, value), value)
        self.assertEqual(value.message, "Hello Alice")
        self.assertIsNone(engine.before_request("Typing", 2, object()))
        engine.set_setting_json(pid, "greeting", '"Welcome"')
        self.assertEqual(json.loads(engine.settings_rows(pid))[0]["value"], "Welcome")
        engine.set_enabled(pid, False)
        self.assertFalse(engine.hooks)
        self.assertFalse(engine.send_hooks)
        engine.set_enabled(pid, True)
        self.assertEqual(engine.get_setting(pid, "greeting"), "Welcome")

    def test_load_error_disables_and_removes_hooks(self):
        pid = self.install(SOURCE.replace('self.add_hook("Typing")', 'raise RuntimeError("broken")'))
        engine.set_enabled(pid, True)
        item = json.loads(engine.list_plugins())[0]
        self.assertFalse(item["enabled"])
        self.assertIn("broken", item["error"])
        self.assertFalse(engine.send_hooks)

    def test_metadata_does_not_execute_on_install(self):
        self.install(SOURCE + '\nraise RuntimeError("must not execute")\n')
        self.assertEqual(len(json.loads(engine.list_plugins())), 1)

    def test_zip_traversal_rejected(self):
        path = pathlib.Path(self.tmp.name) / "bad.plugin"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("../escape.py", SOURCE)
        with self.assertRaises(ValueError): engine.install(str(path))
        self.assertFalse((pathlib.Path(engine.root) / "escape.py").exists())

    def test_zip_then_source_update_keeps_one_entry(self):
        path = pathlib.Path(self.tmp.name) / "zip.plugin"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("main.py", SOURCE)
        pid = engine.install(str(path))
        engine.set_enabled(pid, True)
        self.install()
        self.assertEqual(len(json.loads(engine.list_plugins())), 1)
        self.assertFalse(json.loads(engine.list_plugins())[0]["enabled"])

    def test_invalid_uninstall_id(self):
        with self.assertRaises(ValueError): engine.uninstall("../other")

    def test_sample_plugin(self):
        sample = pathlib.Path(__file__).parent / "plugins/hello_world.plugin"
        pid = self.install(sample.read_text())
        engine.set_enabled(pid, True)
        value = engine.before_send_message(0, SimpleNamespace(message=".hello Alice"))
        self.assertEqual(value.message, "Hello, Alice!")

    def test_replacement_object(self):
        pid = self.install(SOURCE.replace('params.message = self.get_setting', 'from types import SimpleNamespace\n        params = SimpleNamespace(message=params.message)\n        params.message = self.get_setting'))
        engine.set_enabled(pid, True)
        original = SimpleNamespace(message="Alice")
        replacement = engine.before_send_message(0, original)
        self.assertIsNot(replacement, original)
        self.assertEqual(original.message, "Alice")
        self.assertEqual(replacement.message, "Hello Alice")

    def test_interrupted_startup_not_retried(self):
        pid = self.install()
        state = engine._state()
        state["loading"] = pid
        state["enabled"][pid] = True
        engine._write_json(engine._state_path(), state)
        engine.initialize(engine.root)
        self.assertFalse(json.loads(engine.list_plugins())[0]["enabled"])

    def test_uninstall_removes_settings(self):
        pid = self.install()
        engine.set_setting(pid, "secret", "value")
        engine.uninstall(pid)
        self.assertEqual(json.loads(engine.list_plugins()), [])
        self.assertEqual(engine.get_settings(pid), {})

if __name__ == "__main__": unittest.main()
