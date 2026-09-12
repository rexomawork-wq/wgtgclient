"""Host tests of Python ownership and proxy forwarding, with an isolated Java adapter."""
import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch


class JavaThrowable(Exception):
    pass


class Bridge:
    registrations = []
    failure = None

    @classmethod
    def hook(cls, method, callback, priority):
        if cls.failure:
            raise cls.failure
        native = types.SimpleNamespace(calls=0)

        def unhook():
            native.calls += 1
            native.callback = None

        native.unhook = unhook
        native.callback = callback
        cls.registrations.append((method, priority, native))
        return native


class Tests(unittest.TestCase):
    def setUp(self):
        Bridge.registrations = []
        Bridge.failure = None
        java = types.ModuleType("java")
        java.jclass = lambda name: {
            "org.telegram.messenger.WgtgMethodHooks": Bridge,
            "org.telegram.messenger.WgtgMethodHooks$Callback": object,
            "java.lang.Throwable": JavaThrowable,
            "java.lang.RuntimeException": JavaThrowable,
        }[name]
        java.dynamic_proxy = lambda cls: object
        self.patch = patch.dict(sys.modules, {"java": java})
        self.patch.start()
        self.addCleanup(self.patch.stop)
        path = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python/hook_utils.py"
        search_path = patch.object(sys, "path", [str(path.parent), *sys.path])
        search_path.start()
        self.addCleanup(search_path.stop)
        spec = importlib.util.spec_from_file_location("isolated_hook_utils", path)
        self.hooks = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.hooks)

    def test_forwarding_and_owner_isolation(self):
        owner = types.SimpleNamespace()
        other = types.SimpleNamespace()
        events = []
        callback = self.hooks.MethodHook()
        callback.before_hooked_method = lambda param: events.append(("before", param))
        callback.after_hooked_method = lambda param: events.append(("after", param))
        handle = self.hooks.hook_method(owner, "method", callback, 91)
        other_handle = self.hooks.hook_method(other, "method", callback)
        native = Bridge.registrations[0][2]
        native.callback.beforeHookedMethod("param")
        native.callback.afterHookedMethod("param")
        self.assertEqual(events, [("before", "param"), ("after", "param")])
        self.assertEqual(Bridge.registrations[0][:2], ("method", 91))
        self.hooks.unhook_all(owner)
        self.hooks.unhook_all(owner)
        handle.unhook()
        self.assertEqual(native.calls, 1)
        self.assertIsNone(native.callback)
        self.assertEqual(Bridge.registrations[1][2].calls, 0)
        self.assertEqual(owner._wgtg_method_hooks, [])
        with self.assertRaisesRegex(RuntimeError, "unloaded"):
            self.hooks.hook_method(owner, "method", callback)
        self.hooks.unhook_method(other_handle)
        self.assertEqual(other._wgtg_method_hooks, [])

    def test_install_failure_and_validation(self):
        owner = types.SimpleNamespace()
        hook = self.hooks.MethodHook()
        Bridge.failure = RuntimeError("unsupported architecture")
        with self.assertRaisesRegex(RuntimeError, "unsupported architecture"):
            self.hooks.hook_method(owner, "method", hook)
        self.assertEqual(owner._wgtg_method_hooks, [])
        with self.assertRaises(TypeError):
            self.hooks.hook_method(owner, "method", object())
        for priority in (2**31, -2**31 - 1, 1.2):
            with self.assertRaises(ValueError):
                self.hooks.hook_method(owner, "method", hook, priority)

    def test_nested_callbacks_use_owner_environment_and_restore_caller(self):
        import elyx
        from base_plugin import BasePlugin
        environment = {"plugin_id": "owner", "metainfo": {"id": "owner"}}
        owner = BasePlugin()
        owner._wgtg_bind("owner", types.SimpleNamespace(loaded={"owner": {"environment": environment}}))
        observed = []
        hook = self.hooks.MethodHook()
        hook.before_hooked_method = lambda param: observed.append(elyx.metainfo["id"])
        def after(param):
            observed.append(elyx.metainfo["id"])
            raise ValueError("callback failed")
        hook.after_hooked_method = after
        self.hooks.hook_method(owner, "method", hook)
        callback = Bridge.registrations[0][2].callback
        caller = {"plugin_id": "caller", "metainfo": {"id": "caller"}}
        with elyx.environment_scope(caller):
            callback.beforeHookedMethod(None)
            self.assertIs(elyx.get_environment(), caller)
            with self.assertRaisesRegex(ValueError, "callback failed"):
                callback.afterHookedMethod(None)
            self.assertIs(elyx.get_environment(), caller)
        self.assertEqual(observed, ["owner", "owner"])

    def test_replacement_results_and_exceptions(self):
        values = []
        param = types.SimpleNamespace(setResult=lambda value: values.append(("result", value)),
                                      setThrowable=lambda value: values.append(("throwable", value)))
        replacement = self.hooks.MethodReplacement()
        replacement.replace_hooked_method = lambda param: None
        replacement.before_hooked_method(param)
        self.assertEqual(values.pop(), ("result", None))
        for error in (JavaThrowable("java"), ValueError("python"), SystemExit("python")):
            def fail(param):
                raise error
            replacement.replace_hooked_method = fail
            replacement.before_hooked_method(param)
            kind, result = values.pop()
            self.assertEqual(kind, "throwable")
            self.assertIsInstance(result, JavaThrowable)
            if isinstance(error, JavaThrowable):
                self.assertIs(result, error)
            else:
                self.assertEqual(str(result), "python")


if __name__ == "__main__":
    unittest.main()
