import json
import pathlib
import sys
import tempfile
import unittest
import zipfile
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python"))
import wgtg_plugin_engine as engine
import hook_utils

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

    def test_preview_does_not_execute_or_install(self):
        path = pathlib.Path(self.tmp.name) / "preview.plugin"
        path.write_text(SOURCE + '\nraise RuntimeError("must not execute")\n')
        meta = json.loads(engine.inspect_plugin(str(path)))
        self.assertEqual(meta["name"], "Hello")
        self.assertIsNone(meta["installed_version"])
        self.assertEqual(json.loads(engine.list_plugins()), [])
        self.assertEqual(list(pathlib.Path(engine.root).iterdir()), [])
        self.assertFalse(engine.loaded)

    def test_preview_update_keeps_running_plugin_and_settings(self):
        pid = self.install()
        engine.set_enabled(pid, True)
        engine.set_setting(pid, "greeting", "Saved")
        instance = engine.loaded[pid]["instance"]
        path = pathlib.Path(self.tmp.name) / "update.plugin"
        path.write_text(SOURCE + '\n__version__ = "2.0"\n')
        meta = json.loads(engine.inspect_plugin(str(path)))
        self.assertEqual(meta["installed_version"], "1.0")
        self.assertEqual(meta["version"], "2.0")
        self.assertIs(engine.loaded[pid]["instance"], instance)
        self.assertEqual(engine.get_setting(pid, "greeting"), "Saved")

    def test_failed_update_restores_files_and_running_instance(self):
        pid = self.install()
        engine.set_enabled(pid, True)
        instance = engine.loaded[pid]["instance"]
        replace = engine.os.replace
        def fail_install(source, destination):
            if pathlib.Path(source).name.startswith(".install-"):
                raise OSError("disk failure")
            return replace(source, destination)
        with patch.object(engine.os, "replace", side_effect=fail_install):
            with self.assertRaisesRegex(OSError, "disk failure"):
                self.install(SOURCE + '\n__version__ = "2.0"\n')
        self.assertIs(engine.loaded[pid]["instance"], instance)
        plugins = json.loads(engine.list_plugins())
        self.assertEqual(len(plugins), 1)
        self.assertTrue(plugins[0]["enabled"])
        self.assertEqual(plugins[0]["version"], "1.0")

    def test_relative_imports_are_available_lazily_and_refreshed(self):
        path = pathlib.Path(self.tmp.name) / "project.plugin"
        source = SOURCE.replace('self.add_hook("Typing")', 'from .helper import greeting\n        self.set_setting("greeting", greeting)')
        for greeting in ("First", "Other"):
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("main.py", source)
                archive.writestr("helper.py", 'greeting = ' + repr(greeting))
            pid = engine.install(str(path))
            engine.set_enabled(pid, True)
            self.assertEqual(engine.get_setting(pid, "greeting"), greeting)
            self.assertFalse(json.loads(engine.list_plugins())[0]["error"])
        engine.uninstall(pid)
        self.assertNotIn("wgtg_plugin_hello_world.helper", sys.modules)

    def test_absolute_sibling_imports_survive_load_and_refresh_on_update(self):
        path = pathlib.Path(self.tmp.name) / "project.plugin"
        source = SOURCE.replace('    def on_plugin_load(self):',
            '    def __init__(self):\n        import constructor_helper\n        self.constructor_value = constructor_helper.value\n    def on_plugin_load(self):\n        import load_helper')
        source = source.replace('return [Input(key=', 'import settings_helper\n        self.set_setting("lazy", settings_helper.value)\n        return [Input(key=')
        for value in (1, 22):
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("main.py", source)
                for name in ("constructor_helper", "load_helper", "settings_helper"):
                    archive.writestr(name + ".py", "value = " + str(value))
            pid = engine.install(str(path))
            engine.set_enabled(pid, True)
            self.assertFalse(json.loads(engine.list_plugins())[0]["error"])
            self.assertEqual(engine.loaded[pid]["instance"].constructor_value, value)
            engine.settings_rows(pid)
            self.assertEqual(engine.get_setting(pid, "lazy"), value)
        directory = str(pathlib.Path(engine.root) / pid)
        engine.set_enabled(pid, False)
        self.assertNotIn(directory, sys.path)
        for name in ("constructor_helper", "load_helper", "settings_helper"):
            self.assertNotIn(name, sys.modules)

    def test_settings_controls_callbacks_and_expired_pages(self):
        source = '''from base_plugin import BasePlugin
from ui.settings import Header, Divider, Selector, Switch, Input, EditText, Text
__id__ = "settings_test"
__name__ = "Settings"
class Plugin(BasePlugin):
    def changed(self, value):
        self.set_setting("callback_value", value)
    def clicked(self, view):
        self.set_setting("clicked", view)
        return True
    def create_settings(self):
        return [Header("General"), Divider(),
            Selector("choice", "Choice", items=["One", "Two"], on_change=self.changed),
            Switch("toggle", "Toggle", on_change=self.changed),
            Input("input", "Input", on_change=self.changed),
            EditText("edit", "Hint", max_length=4, on_change=self.changed),
            Text("Open", on_click=self.clicked, on_long_click=self.clicked,
                 create_sub_fragment=lambda: [Header("Nested")])]
'''
        pid = self.install(source)
        engine.set_enabled(pid, True)
        rows = json.loads(engine.settings_rows(pid))
        for index, value in ((2, 1), (3, True), (4, "hello"), (5, "four")):
            row = rows[index]
            engine.settings_action(pid, row["token"], "change", json.dumps(value))
            self.assertEqual(engine.get_setting(pid, row["key"]), value)
            self.assertEqual(engine.get_setting(pid, "callback_value"), value)
        for index, value in ((2, -1), (2, True), (3, "true"), (5, "too long")):
            with self.assertRaises(ValueError):
                engine.settings_action(pid, rows[index]["token"], "change", json.dumps(value))
        token = rows[6]["token"]
        self.assertTrue(engine.settings_action(pid, token, "on_click", view="view"))
        self.assertEqual(engine.get_setting(pid, "clicked"), "view")
        self.assertTrue(engine.settings_action(pid, token, "on_long_click", view="long"))
        self.assertEqual(json.loads(engine.settings_rows(pid, token))[0]["text"], "Nested")
        with self.assertRaisesRegex(ValueError, "expired"):
            engine.settings_action(pid, token, "on_click")
        token = json.loads(engine.settings_rows(pid))[2]["token"]
        engine.set_enabled(pid, False)
        with self.assertRaisesRegex(ValueError, "expired"):
            engine.settings_action(pid, token, "change", "0")

    def test_sibling_import_failure_cleans_path_and_modules(self):
        path = pathlib.Path(self.tmp.name) / "failed.plugin"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("main.py", SOURCE + '\nimport failure_helper\nraise RuntimeError("failed")')
            archive.writestr("failure_helper.py", "value = 1")
        pid = engine.install(str(path))
        engine.set_enabled(pid, True)
        self.assertNotIn("failure_helper", sys.modules)
        self.assertNotIn(str(pathlib.Path(engine.root) / pid), sys.path)
        self.assertIn("failed", json.loads(engine.list_plugins())[0]["error"])

    def test_packaged_entry_points_coexist(self):
        first = self.install()
        engine.set_enabled(first, True)
        second = self.install(SOURCE.replace('"hello_world"', '"second_plugin"'))
        engine.set_enabled(second, True)
        self.assertTrue(all(item["enabled"] for item in json.loads(engine.list_plugins())))

    def test_failed_state_save_rolls_back_update(self):
        pid = self.install()
        engine.set_enabled(pid, True)
        instance = engine.loaded[pid]["instance"]
        with patch.object(engine, "_write_json", side_effect=OSError("state disk full")):
            with self.assertRaisesRegex(OSError, "state disk full"):
                self.install(SOURCE + '\n__version__ = "2.0"\n')
        self.assertIs(engine.loaded[pid]["instance"], instance)
        item = json.loads(engine.list_plugins())[0]
        self.assertTrue(item["enabled"])
        self.assertEqual(item["version"], "1.0")

    def test_system_exit_is_a_load_error(self):
        pid = self.install(SOURCE.replace('self.add_hook("Typing")', 'raise SystemExit("exit requested")'))
        engine.set_enabled(pid, True)
        item = json.loads(engine.list_plugins())[0]
        self.assertFalse(item["enabled"])
        self.assertIn("exit requested", item["error"])
        self.assertFalse(engine.send_hooks)

    def test_initialize_is_idempotent(self):
        pid = self.install()
        engine.set_enabled(pid, True)
        engine.initialize(engine.root)
        value = engine.before_send_message(0, SimpleNamespace(message="Alice"))
        self.assertEqual(value.message, "Hello Alice")
        self.assertEqual(len(engine.send_hooks), 1)

    def test_preview_and_install_reject_unsafe_archives(self):
        path = pathlib.Path(self.tmp.name) / "unsafe.plugin"
        for filename in ("../outside", "/absolute", "module.so", "module.whl", "module.pyc"):
            with self.subTest(filename=filename):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("main.py", SOURCE)
                    archive.writestr(filename, "bad")
                for operation in (engine.inspect_plugin, engine.install):
                    with self.assertRaises(ValueError): operation(str(path))
                self.assertEqual(list(pathlib.Path(engine.root).iterdir()), [])

    def test_invalid_metadata_cannot_replace_existing_plugin(self):
        self.install()
        with self.assertRaisesRegex(ValueError, "name"):
            self.install(SOURCE.replace('__name__ = "Hello"', '__name__ = []'))
        self.assertEqual(json.loads(engine.list_plugins())[0]["name"], "Hello")

    def test_hook_priority_modify_final_and_account(self):
        first = SOURCE.replace('"hello_world"', '"first_plugin"').replace('self.add_hook("Typing")', 'self.add_hook("Typing", match_substring=True, priority=10)').replace(
            'return HookResult(strategy=HookStrategy.CANCEL)',
            'return HookResult(strategy=HookStrategy.MODIFY_FINAL, request=(name, account, request))')
        pid = self.install(first)
        engine.set_enabled(pid, True)
        pid = self.install()
        engine.set_enabled(pid, True)
        request = object()
        self.assertEqual(engine.before_request("Typing", 3, request), ("Typing", 3, request))
        self.assertEqual(engine.before_request("TL_messages_setTyping", 2, request), ("TL_messages_setTyping", 2, request))
        self.assertIs(engine.before_request("Other", 1, request), request)


class ReflectionTests(unittest.TestCase):
    def test_inherited_private_and_static_fields(self):
        class Field:
            def setAccessible(self, value): self.accessible = value
            def get(self, obj): return self.value
            def set(self, obj, value): self.value = value
        field = Field()
        field.value = "original"
        parent = SimpleNamespace(getDeclaredField=lambda name: field, getSuperclass=lambda: None)
        def missing(name): raise LookupError(name)
        child = SimpleNamespace(getDeclaredField=missing, getSuperclass=lambda: parent)
        obj = SimpleNamespace(getClass=lambda: child)
        self.assertEqual(hook_utils.get_private_field(obj, "inherited"), "original")
        self.assertTrue(field.accessible)
        self.assertTrue(hook_utils.set_private_field(obj, "inherited", "changed"))
        self.assertEqual(hook_utils.get_static_private_field(child, "inherited"), "changed")
        self.assertTrue(hook_utils.set_static_private_field(child, "inherited", "static"))
        self.assertEqual(field.value, "static")
        self.assertIsNone(hook_utils.get_private_field(None, "missing"))
        self.assertFalse(hook_utils.set_static_private_field(None, "missing", 1))

    def test_find_class_returns_reflection_class_or_none(self):
        clazz = object()
        def jclass(name):
            if name == "missing": raise ValueError(name)
            return SimpleNamespace(class_=clazz)
        with patch.dict(sys.modules, {"java": SimpleNamespace(jclass=jclass)}):
            self.assertIs(hook_utils.find_class("present"), clazz)
            self.assertIsNone(hook_utils.find_class("missing"))

if __name__ == "__main__": unittest.main()
