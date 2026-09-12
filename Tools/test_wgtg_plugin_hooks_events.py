"""Host-side tests for the exteraGram event bridge (no Android runtime required)."""
import pathlib
import sys
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python"))
from base_plugin import AppEvent, BasePlugin, HookResult, HookStrategy
import wgtg_plugin_engine as engine


class HooksEventsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        engine.initialize(self.tmp.name)
        self.addCleanup(engine.shutdown)

    def plugin(self, plugin_id, callback=None, priority=0, name="Target", substring=False):
        instance = BasePlugin()
        instance._wgtg_bind(plugin_id, engine)
        engine.loaded[plugin_id] = {"instance": instance, "meta": {}, "error": ""}
        if callback:
            for method in ("pre_request_hook", "post_request_hook", "on_update_hook", "on_updates_hook", "on_send_message_hook"):
                setattr(instance, method, callback)
        instance.add_hook(name, substring, priority)
        instance.add_on_send_message_hook(priority)
        return instance

    def test_priority_replacement_final_and_account_for_all_hooks(self):
        for method, field in (("before_request", "request"), ("after_request", "response"),
                              ("before_update", "update"), ("before_updates", "updates"),
                              ("before_send_message", "params")):
            with self.subTest(method=method):
                engine.shutdown()
                seen = []
                original, replacement, final = object(), object(), object()
                def modify(*args):
                    seen.append(args)
                    return HookResult(HookStrategy.MODIFY, **{field: replacement})
                def finish(*args):
                    seen.append(args)
                    return HookResult(HookStrategy.MODIFY_FINAL, **{field: final})
                last = Mock(return_value=HookResult(HookStrategy.CANCEL))
                self.plugin("last", last, -1)
                self.plugin("first", modify, 10, "arg", True)
                self.plugin("second", finish, 0)
                args = (3, original) if field == "params" else ("Target", 3, original)
                if field == "response": args += (None,)
                result = getattr(engine, method)(*args)
                self.assertEqual(result, [False, final] if field == "response" else final)
                self.assertEqual(len(seen), 2)
                self.assertIs(seen[1][1 if field == "params" else 2], replacement)
                self.assertEqual(seen[0][0 if field == "params" else 1], 3)
                last.assert_not_called()

    def test_cancel_all_hook_types(self):
        self.plugin("cancel", lambda *args: HookResult(HookStrategy.CANCEL))
        later = Mock()
        self.plugin("later", later, -10)
        for method in ("before_request", "before_update", "before_updates"):
            self.assertIsNone(getattr(engine, method)("Target", 2, object()))
        self.assertIsNone(engine.before_send_message(2, object()))
        self.assertTrue(engine.after_request("Target", 2, None, object())[0])
        later.assert_not_called()

    def test_rpc_error_is_not_cancellation(self):
        error = object()
        callback = Mock(return_value=None)
        self.plugin("observe", callback)
        self.assertEqual(engine.after_request("Target", 4, None, error), [False, None])
        callback.assert_called_once_with("Target", 4, None, error)

    def test_default_ignores_replacement_and_empty_modify_preserves_value(self):
        original = object()
        self.plugin("default", lambda *args: HookResult(response=object()), 10)
        self.plugin("empty", lambda *args: HookResult(HookStrategy.MODIFY))
        self.assertEqual(engine.after_request("Target", 0, original, None), [False, original])

    def test_invalid_registration_does_not_poison_dispatch(self):
        for name in (None, "", 12):
            with self.assertRaises(ValueError): engine.add_hook("invalid", name)
        self.assertFalse(engine.hooks)

    def test_real_menu_registry_cleans_on_unload(self):
        import plugin_menus
        from base_plugin import MenuItemData, MenuItemType
        instance = self.plugin("menus")
        instance.add_menu_item(MenuItemData(MenuItemType.MAIN_MENU, "Test", lambda context: None))
        self.assertIn('"Test"', plugin_menus.visible_items(MenuItemType.MAIN_MENU, {}))
        engine._unload("menus")
        self.assertEqual(plugin_menus.visible_items(MenuItemType.MAIN_MENU, {}), "[]")

    def test_exceptions_and_invalid_strategies_are_isolated(self):
        self.plugin("broken", Mock(side_effect=RuntimeError("broken")), 30)
        self.plugin("exit", Mock(side_effect=SystemExit("exit")), 20)
        self.plugin("invalid", lambda *args: SimpleNamespace(strategy=999), 10)
        value = object()
        observer = Mock(return_value=HookResult())
        self.plugin("observer", observer)
        with patch.object(engine.traceback, "print_exc") as log:
            self.assertIs(engine.before_update("Target", 1, value), value)
        self.assertEqual(log.call_count, 3)
        observer.assert_called_once_with("Target", 1, value)

    def test_matching_and_stable_equal_priority(self):
        seen = []
        self.plugin("exact", lambda *args: seen.append("exact"))
        self.plugin("substring", lambda *args: seen.append("substring"), name="arg", substring=True)
        engine.before_updates("OtherTarget", 0, object())
        self.assertEqual(seen, ["substring"])
        seen.clear()
        engine.before_updates("Target", 0, object())
        self.assertEqual(seen, ["exact", "substring"])

    def test_unload_during_dispatch_skips_old_instance(self):
        replacement = Mock(return_value=None)
        def reload(*args):
            engine._unload("victim")
            self.plugin("victim", replacement)
        self.plugin("first", reload, 10)
        old = Mock()
        self.plugin("victim", old)
        engine.before_request("Target", 0, object())
        old.assert_not_called()
        replacement.assert_not_called()

    def test_events_order_deduplication_error_isolation_and_shutdown(self):
        broken = self.plugin("broken")
        broken.on_app_event = Mock(side_effect=SystemExit("event failure"))
        instance = self.plugin("events")
        seen = []
        instance.on_app_event = seen.append
        instance.on_plugin_unload = lambda: seen.append("unload")
        engine.app_event = None
        with patch.object(engine.traceback, "print_exc"):
            for event in (0, 3, 3, 2, 3): engine.on_app_event(event)
            engine.shutdown()
            engine.shutdown()
        self.assertEqual(seen, [AppEvent.START, AppEvent.RESUME, AppEvent.PAUSE,
                                AppEvent.RESUME, AppEvent.STOP, "unload"])
        self.assertFalse(engine.loaded)
        self.assertFalse(engine.hooks)
        self.assertFalse(engine.send_hooks)

    def test_owned_resources_cleaned_despite_unload_failure(self):
        instance = self.plugin("resources")
        handles = [Mock(), Mock()]
        hook_utils = SimpleNamespace(hook_method=Mock(side_effect=handles), unhook_all=Mock(),
                                     unhook_method=lambda handle: handle.unhook())
        menus = SimpleNamespace(add_menu_item=Mock(return_value="item"), remove_menu_item=Mock(), clear_plugin=Mock())
        with patch.dict(sys.modules, {"hook_utils": hook_utils, "plugin_menus": menus}):
            callback = Mock()
            first = instance.hook_method("member", before=callback)
            call = hook_utils.hook_method.call_args
            self.assertEqual(call.args[:2], (instance, "member"))
            call.args[2].before_hooked_method("param")
            callback.assert_called_once_with("param")
            instance.hook_method("other")
            self.assertEqual(instance.add_menu_item("data"), "item")
            menus.add_menu_item.assert_called_once_with("resources", "data")
            instance.remove_menu_item("item")
            menus.remove_menu_item.assert_called_once_with("resources", "item")
            instance.unhook_method(first)
            instance.on_plugin_unload = Mock(side_effect=RuntimeError("unload"))
            with patch.object(engine.traceback, "print_exc"):
                engine._unload("resources")
            first.unhook.assert_called_once_with()
            hook_utils.unhook_all.assert_called_once_with(instance)
            menus.clear_plugin.assert_called_once_with("resources")

    def test_failed_load_cleans_registered_resources(self):
        source = pathlib.Path(self.tmp.name) / "failed.py"
        source.write_text('from base_plugin import BasePlugin\n'
                          'class Failed(BasePlugin):\n'
                          ' def on_plugin_load(self):\n'
                          '  self.add_hook("Target")\n'
                          '  self.add_on_send_message_hook()\n'
                          '  raise RuntimeError("load failure")\n')
        engine._load("failed", str(source), {})
        self.assertIsNone(engine.loaded["failed"]["instance"])
        self.assertFalse(engine.hooks)
        self.assertFalse(engine.send_hooks)

    def test_unloaded_instance_cannot_register_resources(self):
        instance = self.plugin("closed")
        engine._unload("closed")
        for callback, args in ((instance.add_hook, ("Target",)),
                               (instance.add_on_send_message_hook, ()),
                               (instance.add_menu_item, (None,)),
                               (instance.hook_method, (None,))):
            with self.assertRaisesRegex(RuntimeError, "unloaded"):
                callback(*args)

    def test_registration_racing_unload_cannot_attach_to_replacement(self):
        import plugin_menus
        from base_plugin import MenuItemData, MenuItemType
        for kind in ("hook", "send", "menu"):
            with self.subTest(kind=kind):
                engine.shutdown()
                instance = self.plugin("racing")
                reached = threading.Event()
                errors = []
                lock = engine._lock
                main_thread = threading.get_ident()
                class ObservedLock:
                    def __enter__(self):
                        if threading.get_ident() != main_thread:
                            reached.set()
                        return lock.__enter__()
                    def __exit__(self, *args):
                        return lock.__exit__(*args)
                def register():
                    try:
                        if kind == "hook": instance.add_hook("Target")
                        elif kind == "send": instance.add_on_send_message_hook()
                        else: instance.add_menu_item(MenuItemData(MenuItemType.MAIN_MENU, "Stale", lambda context: None))
                    except Exception as error:
                        errors.append(error)
                    finally:
                        reached.set()
                with patch.object(engine, "_lock", ObservedLock()):
                    with lock:
                        worker = threading.Thread(target=register, daemon=True)
                        worker.start()
                        self.assertTrue(reached.wait(2), "registration did not reach the lock")
                        engine._unload("racing")
                        self.plugin("racing")
                    worker.join(2)
                    self.assertFalse(worker.is_alive())
                self.assertEqual(len(errors), 1)
                self.assertIsInstance(errors[0], RuntimeError)
                self.assertEqual(len(engine.hooks), 1)
                self.assertEqual(len(engine.send_hooks), 1)
                self.assertEqual(plugin_menus.visible_items(MenuItemType.MAIN_MENU, {}), "[]")

    def test_restored_plugin_gets_start_after_load_and_shutdown_keeps_enabled(self):
        source = pathlib.Path(self.tmp.name) / "restored.py"
        source.write_text('from base_plugin import BasePlugin\n'
                          '__id__ = "restored"\n__name__ = "Restored"\n'
                          'class Restored(BasePlugin):\n'
                          ' def on_plugin_load(self): self.events = ["load"]\n'
                          ' def on_app_event(self, event): self.events.append(event)\n'
                          ' def on_plugin_unload(self): self.events.append("unload")\n')
        engine._write_json(engine._state_path(), {"enabled": {"restored": True}, "settings": {}})
        engine.initialize(self.tmp.name)
        instance = engine.loaded["restored"]["instance"]
        self.assertEqual(instance.events, ["load", AppEvent.START])
        engine.shutdown()
        self.assertEqual(instance.events, ["load", AppEvent.START, AppEvent.STOP, "unload"])
        self.assertTrue(engine._state()["enabled"]["restored"])


if __name__ == "__main__":
    unittest.main()
