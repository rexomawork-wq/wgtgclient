"""Real MVEL and Pine dispatcher tests via a host JVM, not an Android/Chaquopy test.

Set HOOK_FILTER_TEST_CLASSPATH to compiled HookFilterHarness/WgtgMethodHooks,
Pine core classes.jar, and mvel2-2.5.2.Final.jar. Requires JPype1.
"""
import os
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python"))
from hook_filters import HookFilter as F, hook_filters
import hook_utils
from Tools.test_method_hooks import Bridge


@unittest.skipUnless(os.environ.get("HOOK_FILTER_TEST_CLASSPATH"), "requires isolated JVM test classpath")
class JVMFilters(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import jpype
        cls.jpype = jpype
        if not jpype.isJVMStarted():
            jpype.startJVM(classpath=os.environ["HOOK_FILTER_TEST_CLASSPATH"].split(os.pathsep),
                            convertStrings=True)
        cls.harness = jpype.JClass("org.telegram.messenger.HookFilterHarness")

    def setUp(self):
        Bridge.registrations = []
        Bridge.failure = None
        def jclass(name):
            if name == "org.telegram.messenger.WgtgMethodHooks":
                return Bridge
            if name == "org.telegram.messenger.WgtgMethodHooks$Callback":
                return object
            return self.jpype.JClass(name)
        self.patch = patch.dict(sys.modules, {"java": types.SimpleNamespace(
            jclass=jclass, dynamic_proxy=lambda interface: object)})
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def param(self, value=None, result=None, receiver=None):
        return self.harness.param(receiver, [value], result)

    def test_documented_mvel_expression_this_object_and_static_calls(self):
        predicate = F.Condition("param.args[0] == object || this instanceof java.lang.String", object=500)
        self.assertTrue(predicate(self.param(self.jpype.JLong(500))))
        self.assertFalse(predicate(self.param(self.jpype.JLong(499))))
        self.assertTrue(predicate(self.param(None, receiver="receiver")))
        self.assertTrue(F.Condition("this == null && object == null")(self.param()))
        # Each evaluation gets a fresh variable map.
        isolated = F.Condition("object = object + 1; object == 501", object=500)
        self.assertTrue(isolated(self.param()))
        self.assertTrue(isolated(self.param()))
        with self.assertRaises(Exception):
            F.Condition("param.noSuchMethod()")(self.param())

    def test_java_runtime_types_preserve_boxing_and_assignability(self):
        integer = self.jpype.JClass("java.lang.Integer")(7)
        param = self.param(integer, result=integer)
        self.assertTrue(F.ArgumentIsInstanceOf(0, "java.lang.Integer")(param))
        self.assertFalse(F.ArgumentIsInstanceOf(0, "java.lang.Long")(param))
        self.assertTrue(F.ResultIsInstanceOf(self.jpype.JClass("java.lang.Number"))(param))
        self.assertFalse(F.ResultIsInstanceOf(self.jpype.JClass("java.lang.Long").class_)(param))
        self.assertFalse(F.ResultIsInstanceOf("java.lang.Object")(self.param()))
        self.assertTrue(F.ArgumentIsInstanceOf(0, "java.lang.CharSequence")(self.param("text")))
        self.assertFalse(F.ArgumentIsInstanceOf(1, "java.lang.Object")(param))

    def test_decorated_hook_through_real_java_dispatcher(self):
        events = []
        class Hook(hook_utils.MethodHook):
            @hook_filters(F.Condition("param.args[0] == object", object="change"))
            def before_hooked_method(self, param):
                events.append("before")
                param.args[0] = "changed"
            @hook_filters(F.ResultEqual("changed"))
            def after_hooked_method(self, param):
                events.append("after")
                param.setResult("filtered result")
        hook_utils.hook_method(types.SimpleNamespace(), "method", Hook())
        callback = self.jpype.JProxy("org.telegram.messenger.WgtgMethodHooks$Callback",
                                    inst=Bridge.registrations[-1][2].callback)
        untouched = self.harness.invoke(callback, None, ["untouched"])
        self.assertEqual(untouched.getResult(), "untouched")
        changed = self.harness.invoke(callback, None, ["change"])
        self.assertEqual(changed.getResult(), "filtered result")
        self.assertEqual(events, ["before", "after"])

    def test_filter_failure_preserves_original_in_dispatcher(self):
        class Hook(hook_utils.MethodHook):
            @hook_filters(F.Condition("param.noSuchMethod()"))
            def before_hooked_method(self, param):
                param.setResult("must not run")
            @hook_filters(F.Condition("param.noSuchMethod()"))
            def after_hooked_method(self, param):
                param.setResult("must not run")
        hook_utils.hook_method(types.SimpleNamespace(), "method", Hook())
        callback = self.jpype.JProxy("org.telegram.messenger.WgtgMethodHooks$Callback",
                                    inst=Bridge.registrations[-1][2].callback)
        frame = self.harness.invoke(callback, None, ["original"])
        self.assertEqual(frame.getResult(), "original")
        self.assertFalse(frame.hasThrowable())

    def test_filtered_replacement_keeps_original_eligible(self):
        class Replacement(hook_utils.MethodReplacement):
            @hook_filters(F.Condition("param.args[0] == object", object="replace"))
            def replace_hooked_method(self, param):
                return None
        hook_utils.hook_method(types.SimpleNamespace(), "method", Replacement())
        callback = self.jpype.JProxy("org.telegram.messenger.WgtgMethodHooks$Callback",
                                    inst=Bridge.registrations[-1][2].callback)
        self.assertEqual(self.harness.invoke(callback, None, ["original"]).getResult(), "original")
        self.assertIsNone(self.harness.invoke(callback, None, ["replace"]).getResult())


if __name__ == "__main__":
    unittest.main()
