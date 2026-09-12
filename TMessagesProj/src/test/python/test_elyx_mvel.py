"""Exercise the actual Python MVEL adapter against the published JVM library.

Set ELYX_TEST_MVEL_JAR and provide JPype1 on PYTHONPATH to run.
"""
import os
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main/python"))
import elyx


@unittest.skipUnless(os.environ.get("ELYX_TEST_MVEL_JAR"), "requires MVEL jar and JPype1")
class MvelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import jpype
        cls.jvm = jpype
        if not jpype.isJVMStarted():
            jpype.startJVM(classpath=[os.environ["ELYX_TEST_MVEL_JAR"]], convertStrings=True)

    def setUp(self):
        bridge = types.SimpleNamespace(jclass=self.jvm.JClass,
                                       cast=lambda name, value: self.jvm.JObject(value, self.jvm.JClass(name)))
        self.mock = patch.dict(sys.modules, {"java": bridge})
        self.mock.start()
        self.addCleanup(self.mock.stop)

    def test_values_this_type_conversion_and_errors(self):
        self.assertTrue(elyx.mvel_execute("enabled && count > 0", {"enabled": True, "count": 3}))
        self.assertEqual(elyx.mvel_execute("count + 2", {"count": 3}), 5)
        receiver = self.jvm.JString("hello")
        self.assertEqual(elyx.mvel_execute("this.length() + count", {"count": 2}, java_instance=receiver), 7)
        string_type = self.jvm.JClass("java.lang.String").class_
        self.assertEqual(elyx.mvel_execute("count + 2", {"count": 3}, to_type=string_type), "5")
        with self.assertRaises(Exception):
            elyx.mvel_execute("unknown.noSuchMethod()", {})

    def test_hashmap_and_input_validation(self):
        values = self.jvm.JClass("java.util.HashMap")()
        values.put("value", 42)
        self.assertEqual(elyx.mvel_execute("value", values), 42)
        with self.assertRaises(TypeError):
            elyx.mvel_execute("true", [])
        with self.assertRaises(TypeError):
            elyx.mvel_execute(None, {})


if __name__ == "__main__":
    unittest.main()
