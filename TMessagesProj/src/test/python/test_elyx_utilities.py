import importlib
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main/python"))
import elyx


class ElyxTest(unittest.TestCase):
    def test_environment_scope_restoration_and_dynamic_exports(self):
        with self.assertRaises(RuntimeError):
            elyx.get_environment()
        outer = {"metainfo": {"id": "a"}}
        with elyx.environment_scope(outer):
            self.assertIs(elyx.metainfo, outer["metainfo"])
            with elyx.environment_scope({"metainfo": {"id": "b"}}):
                self.assertEqual(elyx.metainfo["id"], "b")
            self.assertIs(elyx.get_environment(), outer)
            with self.assertRaises(AttributeError):
                getattr(elyx, "assets")

    def test_namespaced_caller(self):
        env = {"plugin_id": "a"}
        elyx.register_environment("plugin_a", env)
        try:
            namespace = {"__name__": "plugin_a.feature", "elyx": elyx}
            exec("result = elyx.get_environment()", namespace)
            self.assertIs(namespace["result"], env)
        finally:
            elyx.unregister_environment("plugin_a")

    def test_assets_content_names_and_traversal(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)
            (path / "empty-state.json").write_text('{"ok": true}')
            (path / "binary.bin").write_bytes(b"\xff")
            assets = elyx.Assets(root)
            self.assertEqual(assets.empty_state.content(), {"ok": True})
            self.assertEqual(assets.binary.content(), b"\xff")
            self.assertEqual(len(assets), 2)
            with self.assertRaises(ValueError):
                assets["../escape"]
            with self.assertRaises(elyx.AssetNotFoundException):
                assets["missing"]
            (path / "outside").symlink_to(path.parent)
            with self.assertRaises(ValueError):
                assets["outside"]

    def test_strings_fallback_format_plural_and_copy(self):
        catalog = elyx.Strings({"en": {"hello": "Hello {name}", "forms": ["file", "files", "files"]}, "ru": {"hello": "Privet {name}"}})
        with elyx.environment_scope({"locale": "ru"}):
            self.assertEqual(catalog("hello", name="A"), "Privet A")
            self.assertEqual(catalog("missing"), "missing")
            self.assertEqual(catalog.pluralize(21, "forms"), "21 file")
            value = catalog.get("forms")
            value[0] = "changed"
            self.assertEqual(catalog.pluralize(11, "forms"), "11 files")

    def test_settings_contract(self):
        backend = Mock()
        backend.get_settings.return_value = {"enabled": True}
        backend.get_setting.return_value = True
        with elyx.environment_scope({"plugin_id": "demo", "settings_backend": backend}):
            settings = elyx.SettingsController("demo")
            self.assertTrue(settings["enabled"])
            settings["enabled"] = False
            backend.set_setting.assert_called_once_with("demo", "enabled", False)
            settings.clear_settings()
            backend.replace_settings.assert_called_once_with("demo", {})

    def test_local_import_does_not_hide_internal_missing_dependency(self):
        with elyx.environment_scope({"package": "plugin_demo"}):
            with patch("importlib.import_module", side_effect=ModuleNotFoundError("missing", name="dependency")) as load:
                with self.assertRaises(ModuleNotFoundError):
                    elyx.import_module("feature")
                load.assert_called_once_with("plugin_demo.feature")

    def test_java_wrappers_with_recording_bridge(self):
        classes = {}
        def jclass(name):
            if name not in classes:
                classes[name] = Mock(name=name)
            return classes[name]
        java = types.ModuleType("java")
        java.jclass = jclass
        java.dynamic_proxy = lambda interface: type("JavaProxy", (), {})
        modules = ("android_utils", "client_utils", "ui.alert", "ui.bulletin")
        saved = {name: sys.modules.pop(name) for name in modules if name in sys.modules}
        try:
            with patch.dict(sys.modules, {"java": java}):
                android = importlib.import_module("android_utils")
                client = importlib.import_module("client_utils")
                alert = importlib.import_module("ui.alert")
                bulletin = importlib.import_module("ui.bulletin")
                android_class = jclass("org.telegram.messenger.AndroidUtilities")
                android_class.runOnUIThread.side_effect = lambda runnable, delay: runnable.run()
                cb = Mock(return_value=True)
                self.assertTrue(android.OnLongClickListener(cb).onLongClick("view"))
                cb.assert_called_once_with("view")
                with patch("elyx._proxies.traceback.print_exc") as log_error:
                    self.assertFalse(android.OnLongClickListener(lambda view: 1 / 0).onLongClick("view"))
                    log_error.assert_called_once()
                result = []
                elyx.Callback2(lambda a, b, extra: result.append((a, b, extra)), "extra").run(1, 2)
                self.assertEqual(result, [(1, 2, "extra")])
                environment = {"plugin_id": "callback_owner"}
                with elyx.environment_scope(environment):
                    callback = elyx.CallbackReturn(lambda value: elyx.get_environment()["plugin_id"])
                with elyx.environment_scope({"plugin_id": "other"}):
                    self.assertEqual(callback.run(None), "callback_owner")
                    self.assertEqual(elyx.get_environment()["plugin_id"], "other")
                with client.account_scope(2):
                    client.get_messages_controller()
                jclass("org.telegram.messenger.MessagesController").getInstance.assert_called_with(2)
                jclass("org.telegram.messenger.UserConfig").selectedAccount = 0
                observed = []
                client.send_request("req", lambda response, error: observed.append(client._selected()), account=3)
                manager = jclass("org.telegram.tgnet.ConnectionsManager").getInstance.return_value
                manager.sendRequest.call_args.args[1].run("response", None)
                self.assertEqual(observed, [3])
                self.assertEqual(client._selected(), 0)
                import wgtg_plugin_engine as engine
                from base_plugin import BasePlugin
                with tempfile.TemporaryDirectory() as root:
                    engine.initialize(root)
                    try:
                        instance = BasePlugin()
                        instance._wgtg_bind("account_test", engine)
                        engine.loaded["account_test"] = {"instance": instance, "meta": {}}
                        instance.add_hook("Account")
                        instance.add_on_send_message_hook()
                        seen = []
                        def observe(*args):
                            seen.append(client._selected())
                            client.get_messages_controller()
                            jclass("org.telegram.messenger.MessagesController").getInstance.assert_called_with(3)
                            engine.before_request("Nested", 1, None)
                            self.assertEqual(client._selected(), 3)
                            raise ValueError("restore account on failure")
                        for method in ("pre_request_hook", "post_request_hook", "on_update_hook", "on_updates_hook", "on_send_message_hook"):
                            setattr(instance, method, observe)
                        with client.account_scope(2), patch.object(engine.traceback, "print_exc") as errors:
                            engine.before_request("Account", 3, None)
                            engine.after_request("Account", 3, None, None)
                            engine.before_update("Account", 3, None)
                            engine.before_updates("Account", 3, None)
                            engine.before_send_message(3, None)
                            self.assertEqual(client._selected(), 2)
                        self.assertEqual(seen, [3] * 5)
                        self.assertEqual(errors.call_count, 5)
                        self.assertEqual(client._selected(), 0)
                    finally:
                        engine.shutdown()
                dialog = alert.AlertDialogBuilder("activity")
                clicked = Mock()
                dialog.set_positive_button("OK", clicked).show()
                builder = jclass("org.telegram.ui.ActionBar.AlertDialog$Builder").return_value
                builder.setPositiveButton.call_args.args[1].onClick("java_dialog", -1)
                clicked.assert_called_once_with(dialog, -1)
                dialog.show()
                builder.create.assert_called_once()
                bulletin.BulletinHelper.show_error("error", "fragment")
                factory = jclass("org.telegram.ui.Components.BulletinFactory").of.return_value
                factory.createErrorBulletin.assert_called_once_with("error")
                factory.createErrorBulletin.return_value.show.assert_called_once()
        finally:
            for name in modules:
                sys.modules.pop(name, None)
            sys.modules.update(saved)


if __name__ == "__main__":
    unittest.main()
