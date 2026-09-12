"""Predicate and production callback-path tests; no Android or JVM required."""
from pathlib import Path
import sys
import types
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/python"))
from hook_filters import HookFilter as F, hook_filters
from base_plugin import BasePlugin, HookFilter, hook_filters as exported_decorator
from Tools import test_method_hooks


class Param:
    def __init__(self, args=(), result=None):
        self.args = list(args)
        self.result = result
        self.set_results = []

    def getResult(self):
        return self.result

    def setResult(self, result):
        self.result = result
        self.set_results.append(result)


class Predicates(unittest.TestCase):
    def test_null_and_strict_boolean_results(self):
        filters = (F.RESULT_IS_NULL, F.RESULT_NOT_NULL, F.RESULT_IS_TRUE, F.RESULT_IS_FALSE)
        for value, expected in ((None, (True, False, False, False)),
                                (True, (False, True, True, False)),
                                (False, (False, True, False, True)),
                                (0, (False, True, False, False)),
                                (1, (False, True, False, False)),
                                ("", (False, True, False, False))):
            with self.subTest(value=value):
                self.assertEqual(tuple(predicate(Param(result=value)) for predicate in filters), expected)

    def test_argument_predicates_and_missing_arguments(self):
        factories = (F.ArgumentIsNull, F.ArgumentNotNull, F.ArgumentIsTrue, F.ArgumentIsFalse)
        for value in (None, True, False, 0, 1, "", "text"):
            expected = (value is None, value is not None, value is True, value is False)
            self.assertEqual(tuple(factory(0)(Param([value])) for factory in factories), expected)
        for factory in (*factories, lambda i: F.ArgumentEqual(i, None),
                        lambda i: F.ArgumentNotEqual(i, "anything")):
            self.assertFalse(factory(0)(Param()))
            for index in (-1, 1.5, True, "0"):
                with self.assertRaises(ValueError):
                    factory(index)

    def test_equality(self):
        for left, right, expected in ((None, None, True), (None, "", False),
                                      (500, 500, True), (5, 5.0, True),
                                      (True, 1, False), (False, 0, False),
                                      ("same", "same", True), ("a", "b", False)):
            param = Param([left], left)
            self.assertEqual(F.ResultEqual(right)(param), expected)
            self.assertEqual(F.ResultNotEqual(right)(param), not expected)
            self.assertEqual(F.ArgumentEqual(0, right)(param), expected)
            self.assertEqual(F.ArgumentNotEqual(0, right)(param), not expected)

    def test_and_or_short_circuit_stacking_and_errors(self):
        events = []
        def fail(param):
            raise ValueError("predicate failed")
        self.assertTrue(F.Or(lambda p: True, fail)(Param()))
        self.assertFalse(F.Or()(Param()))

        @hook_filters(lambda p: False, fail)
        def skipped(param):
            events.append("incorrect")
        skipped(Param())

        @hook_filters(F.ArgumentNotNull(0))
        @hook_filters(F.ArgumentEqual(0, "go"))
        def stacked(param):
            events.append("ran")
            return 7
        stacked(Param([None]))
        stacked(Param(["stop"]))
        self.assertEqual(stacked(param=Param(["go"])), 7)
        self.assertEqual(events, ["ran"])
        self.assertEqual(stacked.__name__, "stacked")
        with self.assertRaisesRegex(ValueError, "predicate failed"):
            hook_filters(fail)(lambda p: None)(Param())
        for factory in (hook_filters, F.Or, F):
            with self.assertRaises(TypeError):
                factory(None)
        for condition in (None, "", "  ", lambda p: True):
            with self.assertRaises(ValueError):
                F.Condition(condition)
        for clazz in (None, object, 123):
            with self.assertRaises(TypeError):
                F.ResultIsInstanceOf(clazz)


class CallbackPath(unittest.TestCase):
    def setUp(self):
        test_method_hooks.Tests.setUp(self)

    def test_decorated_methods_through_registered_proxy(self):
        events = []
        class Hook(self.hooks.MethodHook):
            @hook_filters(F.ArgumentIsNull(0))
            def before_hooked_method(self, param):
                events.append("before")
            @hook_filters(F.RESULT_NOT_NULL)
            def after_hooked_method(self, param):
                events.append("after")

        self.hooks.hook_method(types.SimpleNamespace(), "method", Hook())
        callback = test_method_hooks.Bridge.registrations[-1][2].callback
        callback.beforeHookedMethod(Param(["no"]))
        callback.beforeHookedMethod(Param([None]))
        callback.afterHookedMethod(Param(result=None))
        callback.afterHookedMethod(Param(result=0))
        self.assertEqual(events, ["before", "after"])

    def test_functional_filters_and_base_plugin_exports(self):
        self.assertIs(HookFilter, F)
        self.assertIs(exported_decorator, hook_filters)
        owner = BasePlugin()
        owner._wgtg_bind("test", None)
        events = []
        @hook_filters(F.ArgumentEqual(0, "go"))
        def before(param):
            events.append("before")
        owner.hook_method("method", before=before, before_filters=[F.ArgumentNotNull(0)],
                          after=lambda p: events.append("after"), after_filters=[F.RESULT_IS_FALSE])
        callback = test_method_hooks.Bridge.registrations[-1][2].callback
        callback.beforeHookedMethod(Param([None]))
        callback.beforeHookedMethod(Param(["stop"]))
        callback.beforeHookedMethod(Param(["go"]))
        callback.afterHookedMethod(Param(result=0))
        callback.afterHookedMethod(Param(result=False))
        self.assertEqual(events, ["before", "after"])

    def test_filtered_replacement_does_not_set_null_result(self):
        class Replacement(self.hooks.MethodReplacement):
            @hook_filters(F.ArgumentIsNull(0))
            def replace_hooked_method(self, param):
                return None
        self.hooks.hook_method(types.SimpleNamespace(), "method", Replacement())
        callback = test_method_hooks.Bridge.registrations[-1][2].callback
        rejected = Param(["original must run"])
        callback.beforeHookedMethod(rejected)
        self.assertEqual(rejected.set_results, [])
        accepted = Param([None])
        callback.beforeHookedMethod(accepted)
        self.assertEqual(accepted.set_results, [None])


if __name__ == "__main__":
    unittest.main()
