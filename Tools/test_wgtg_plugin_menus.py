import gc
import json
import pathlib
import sys
import tempfile
import threading
import unittest
import weakref
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python"))
import plugin_menus as menus

SOURCE = '''from base_plugin import BasePlugin, MenuItemData, MenuItemType
__id__ = "menu_test"
__name__ = "Menu Test"
class MenuPlugin(BasePlugin):
    def on_plugin_load(self):
        self.calls = []
        for kind in MenuItemType:
            self.add_menu_item(MenuItemData(kind, kind.name, self.calls.append))
    def on_plugin_unload(self):
        self.add_menu_item(MenuItemData(MenuItemType.MAIN_MENU, "Unload item", self.calls.append))
'''


class PluginMenuTests(unittest.TestCase):
    def setUp(self):
        with menus._lock:
            menus._items.clear()
        self.invalidate = patch.object(menus, "_invalidate").start()
        self.addCleanup(patch.stopall)
        self.addCleanup(menus._items.clear)
        self.context = {"account": 2, "dialog_id": -123, "message": object(), "fragment": object()}

    def add(self, owner="example", **kwargs):
        values = dict(menu_type=menus.MenuItemType.MESSAGE_CONTEXT_MENU, text="Example", on_click=lambda context: None)
        values.update(kwargs)
        return menus.add_menu_item(owner, menus.MenuItemData(**values))

    def rows(self, menu_type=menus.MenuItemType.MESSAGE_CONTEXT_MENU, context=None):
        return json.loads(menus.visible_items(menu_type, self.context if context is None else context))

    def test_types_priority_and_registration_order(self):
        self.add(text="low", priority=-1)
        self.add(text="first", priority=10)
        self.add(text="second", priority=10)
        self.add(text="profile", menu_type=menus.MenuItemType.PROFILE_ACTION_MENU)
        self.assertEqual([row["text"] for row in self.rows()], ["first", "second", "low"])
        self.assertEqual([row["text"] for row in self.rows(4)], ["profile"])
        self.assertEqual(self.rows(1), [])

    def test_click_gets_dictionary_with_live_objects_and_actual_account(self):
        callback = Mock()
        self.add(on_click=callback)
        self.assertTrue(menus.click(self.rows()[0]["token"], self.context))
        received = callback.call_args.args[0]
        self.assertIsInstance(received, dict)
        self.assertIsNot(received, self.context)
        self.assertIs(received["message"], self.context["message"])
        self.assertIs(received["fragment"], self.context["fragment"])
        self.assertEqual(received["account"], 2)
        self.assertEqual(received["dialog_id"], -123)

    def test_drawer_is_distinct_from_main_and_unload_invalidates_both(self):
        callback = Mock()
        self.add(menu_type=menus.MenuItemType.DRAWER_MENU, text="Drawer", on_click=callback,
                 visible=lambda context: context["account"] == 2)
        self.add(menu_type=menus.MenuItemType.MAIN_MENU, text="Main")
        context = {"account": 2, "context": object(), "fragment": object(), "dialog_id": 0}
        drawer = self.rows(menus.MenuItemType.DRAWER_MENU, context)
        main = self.rows(menus.MenuItemType.MAIN_MENU, context)
        self.assertEqual([row["text"] for row in drawer], ["Drawer"])
        self.assertEqual([row["text"] for row in main], ["Main"])
        self.assertTrue(menus.click(drawer[0]["token"], context))
        self.assertEqual(callback.call_args.args[0], context)
        self.assertEqual(self.rows(1, dict(context, account=3)), [])
        self.assertEqual(menus.clear_plugin("example"), 2)
        self.assertFalse(menus.click(drawer[0]["token"], context))
        self.assertFalse(menus.click(main[0]["token"], context))

    def test_java_map_context_is_converted_without_serializing_values(self):
        entries = [SimpleNamespace(getKey=lambda key=key: key, getValue=lambda value=value: value)
                   for key, value in self.context.items()]
        native = SimpleNamespace(entrySet=lambda: entries)
        callback = Mock()
        self.add(on_click=callback)
        self.assertTrue(menus.click(self.rows(context=native)[0]["token"], native))
        self.assertEqual(callback.call_args.args[0], self.context)

    def test_visibility_is_rechecked_at_open_and_click(self):
        state = {"visible": False}
        callback = Mock()
        self.add(visible=lambda context: state["visible"] and context["account"] == 2, on_click=callback)
        self.assertEqual(self.rows(), [])
        state["visible"] = True
        token = self.rows()[0]["token"]
        state["visible"] = False
        self.assertFalse(menus.click(token, self.context))
        callback.assert_not_called()

    def test_visibility_context_mutation_does_not_affect_other_items(self):
        def mutate(context):
            context["account"] = 99
            return True
        self.add(visible=mutate)
        callback = Mock()
        self.add(visible=lambda context: context["account"] == 2, on_click=callback)
        self.assertEqual(len(self.rows()), 2)
        self.assertTrue(menus.click(self.rows()[1]["token"], self.context))
        self.assertEqual(callback.call_args.args[0]["account"], 2)

    def test_mvel_condition_is_forwarded_with_native_context(self):
        bridge = SimpleNamespace(evaluateCondition=Mock(return_value=False))
        with patch.dict(sys.modules, {"java": SimpleNamespace(jclass=lambda name: bridge)}):
            self.add(condition="account == 2 && message != null")
            self.assertEqual(self.rows(), [])
            bridge.evaluateCondition.assert_called_with("account == 2 && message != null", self.context)
            bridge.evaluateCondition.return_value = True
            self.assertEqual(len(self.rows()), 1)

    def test_visibility_and_click_errors_do_not_escape(self):
        def fail(context):
            raise SystemExit("broken plugin")
        with patch.object(menus.traceback, "print_exc") as log:
            self.add(visible=fail)
            self.add(on_click=fail)
            self.assertEqual(len(self.rows()), 1)
            self.assertFalse(menus.click(self.rows()[0]["token"], self.context))
            self.assertGreaterEqual(log.call_count, 2)

    def test_scoped_removal_and_stale_registration_tokens(self):
        callback = Mock()
        self.add(item_id="stable", on_click=callback)
        self.add(owner="other", item_id="stable")
        old = self.rows()[0]["token"]
        self.add(item_id="stable", on_click=callback)
        self.assertFalse(menus.click(old, self.context))
        self.invalidate.assert_called_with([old])
        self.assertTrue(menus.remove_menu_item("example", "stable"))
        self.assertFalse(menus.remove_menu_item("example", "stable"))
        self.assertEqual(len(self.rows()), 1)
        callback.assert_not_called()

    def test_unload_releases_callbacks_and_invalidates_open_rows(self):
        class Plugin:
            def click(self, context):
                pass
        plugin = Plugin()
        reference = weakref.ref(plugin)
        self.add(on_click=plugin.click)
        self.add(menu_type=menus.MenuItemType.CHAT_ACTION_MENU, on_click=plugin.click)
        self.add(owner="other")
        token = self.rows()[0]["token"]
        del plugin
        self.assertEqual(menus.clear_plugin("example"), 2)
        gc.collect()
        self.assertIsNone(reference())
        self.assertFalse(menus.click(token, self.context))
        self.assertEqual(len(self.rows()), 1)
        self.assertEqual(self.rows(3), [])
        self.assertEqual(menus.clear_plugin("example"), 0)

    def test_callback_can_remove_itself(self):
        self.add(item_id="self", on_click=lambda context: menus.remove_menu_item("example", "self"))
        self.assertTrue(menus.click(self.rows()[0]["token"], self.context))
        self.assertEqual(self.rows(), [])

    def test_unload_during_visibility_filters_snapshot_without_deadlock(self):
        def visible(context):
            worker = threading.Thread(target=menus.clear_plugin, args=("example",))
            worker.start()
            worker.join(timeout=2)
            self.assertFalse(worker.is_alive(), "registry lock held while calling plugin")
            return True
        self.add(visible=visible)
        self.assertEqual(self.rows(), [])

    def test_registration_copies_data_and_validates_before_replacing(self):
        data = menus.MenuItemData(menus.MenuItemType.MAIN_MENU, "Original", lambda context: None, item_id="stable")
        self.assertEqual(menus.add_menu_item("example", data), "stable")
        data.text = "Changed"
        self.assertEqual(self.rows(2)[0]["text"], "Original")
        for kwargs in ({"text": ""}, {"on_click": None}, {"visible": True}, {"condition": lambda context: True}, {"menu_type": 99}):
            with self.assertRaises((ValueError, TypeError)):
                self.add(item_id="stable", **kwargs)
        self.assertEqual(self.rows(2)[0]["text"], "Original")


class PluginMenuLifecycleTests(unittest.TestCase):
    def setUp(self):
        import wgtg_plugin_engine
        self.engine = wgtg_plugin_engine
        for plugin_id in list(self.engine.loaded):
            self.engine._unload(plugin_id)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(lambda: self.engine._unload("menu_test"))
        self.engine.initialize(str(pathlib.Path(self.temp.name) / "plugins"))
        self.source = pathlib.Path(self.temp.name) / "input.plugin"
        self.source.write_text(SOURCE)

    def rows(self):
        return json.loads(menus.visible_items(0, {"account": 2, "message": self}))

    def test_install_disable_replace_reinitialize_and_uninstall(self):
        self.engine.install(str(self.source))
        self.assertEqual(self.rows(), [])
        self.engine.set_enabled("menu_test", True)
        self.assertEqual(len(self.rows()), 1)
        instance = self.engine.loaded["menu_test"]["instance"]
        token = self.rows()[0]["token"]
        self.assertTrue(menus.click(token, {"account": 2, "message": self}))
        self.assertIs(instance.calls[0]["message"], self)
        self.engine.set_enabled("menu_test", False)
        self.assertEqual(self.rows(), [])
        self.assertFalse(menus.click(token, {}))
        self.assertEqual(menus.clear_plugin("menu_test"), 0)
        self.engine.set_enabled("menu_test", True)
        token = self.rows()[0]["token"]
        self.engine.initialize(self.engine.root)
        self.assertEqual(len(self.rows()), 1)
        self.assertFalse(menus.click(token, {}))
        self.engine.install(str(self.source))
        self.assertEqual(menus.clear_plugin("menu_test"), 0)
        self.engine.set_enabled("menu_test", True)
        self.engine.uninstall("menu_test")
        self.assertEqual(menus.clear_plugin("menu_test"), 0)

    def test_failed_load_and_throwing_unload_clear_all_registrations(self):
        self.source.write_text(SOURCE.replace(
            '    def on_plugin_unload(self):',
            '        raise RuntimeError("load failed")\n    def on_plugin_unload(self):'
        ) + '        raise RuntimeError("unload failed")\n')
        self.engine.install(str(self.source))
        with patch.object(self.engine.traceback, "print_exc"):
            self.engine.set_enabled("menu_test", True)
        self.assertIsNone(self.engine.loaded["menu_test"]["instance"])
        self.assertEqual(menus.clear_plugin("menu_test"), 0)
        self.assertEqual(self.rows(), [])


if __name__ == "__main__":
    unittest.main()
