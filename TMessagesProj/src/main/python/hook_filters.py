"""exteraGram hook predicates: https://plugins.exteragram.app/docs/xposed-hooking.

Multiple filters are ANDed, Or short-circuits, and missing arguments never match.
Condition uses the bundled MVEL interpreter with param, object and the receiver
as this. Predicate errors propagate to the hook bridge's exception handling.
"""

from functools import wraps

__all__ = ["HookFilter", "hook_filters"]
_SKIPPED = object()


def hook_filters(*filters):
    """Filter a bound hook method or a functional callback at invocation time."""
    if not all(callable(predicate) for predicate in filters):
        raise TypeError("Hook filters must be callable predicates")

    def decorate(callback):
        @wraps(callback)
        def filtered(*args, **kwargs):
            param = kwargs["param"] if "param" in kwargs else args[-1]
            if all(predicate(param) for predicate in filters):
                return callback(*args, **kwargs)
            return _SKIPPED
        return filtered
    return decorate


def _equal(left, right):
    # Java Boolean is unboxed to bool by Chaquopy; it must not equal numeric 0/1.
    if (isinstance(left, (bool, int, float)) and isinstance(right, (bool, int, float))
            and isinstance(left, bool) != isinstance(right, bool)):
        return False
    return left == right


def _argument(index, predicate):
    if type(index) is not int or index < 0:
        raise ValueError("Argument index must be a non-negative integer")
    return HookFilter(lambda param: index < len(param.args) and predicate(param.args[index]))


def _instance_filter(expression, clazz):
    if isinstance(clazz, str):
        from java import jclass
        clazz = jclass(clazz)
    if isinstance(clazz, type):
        # Accept both Java class proxies and reflected java.lang.Class objects.
        if hasattr(clazz, "class_"):
            clazz = clazz.class_
        elif callable(getattr(clazz, "getClass", None)):
            clazz = clazz.getClass()
    if not callable(getattr(clazz, "isInstance", None)):
        raise TypeError("clazz must be a Java class, reflected Class, or class name")
    # Keep the value in Java: a Python round trip loses boxed Integer/Long types.
    return HookFilter.Condition(expression + " instanceof object", object=clazz)


class HookFilter:
    def __init__(self, predicate):
        if not callable(predicate):
            raise TypeError("Hook filter must be a callable predicate")
        self._predicate = predicate

    def __call__(self, param):
        return bool(self._predicate(param))

    @staticmethod
    def ResultIsInstanceOf(clazz):
        return _instance_filter("param.getResult()", clazz)

    @staticmethod
    def ResultEqual(value):
        return HookFilter(lambda param: _equal(param.getResult(), value))

    @staticmethod
    def ResultNotEqual(value):
        return HookFilter(lambda param: not _equal(param.getResult(), value))

    @staticmethod
    def ArgumentIsNull(index):
        return _argument(index, lambda value: value is None)

    @staticmethod
    def ArgumentNotNull(index):
        return _argument(index, lambda value: value is not None)

    @staticmethod
    def ArgumentIsFalse(index):
        return _argument(index, lambda value: value is False)

    @staticmethod
    def ArgumentIsTrue(index):
        return _argument(index, lambda value: value is True)

    @staticmethod
    def ArgumentIsInstanceOf(index, clazz):
        valid = _argument(index, lambda value: True)
        matches = _instance_filter("param.args[" + str(index) + "]", clazz)
        return HookFilter(lambda param: valid(param) and matches(param))

    @staticmethod
    def ArgumentEqual(index, value):
        return _argument(index, lambda argument: _equal(argument, value))

    @staticmethod
    def ArgumentNotEqual(index, value):
        return _argument(index, lambda argument: not _equal(argument, value))

    @staticmethod
    def Condition(condition, object=None):
        if not isinstance(condition, str) or not condition.strip():
            raise ValueError("Condition must be a non-empty MVEL expression")

        def evaluate(param):
            from java import jclass
            variables = jclass("java.util.HashMap")()
            variables.put("param", param)
            variables.put("object", object)
            # Interpret each call, avoiding JVM bytecode generation on Android.
            return bool(jclass("org.mvel2.MVEL").evalToBoolean(condition, param.thisObject, variables))

        return HookFilter(evaluate)

    @staticmethod
    def Or(*filters):
        if not all(callable(predicate) for predicate in filters):
            raise TypeError("Hook filters must be callable predicates")
        return HookFilter(lambda param: any(predicate(param) for predicate in filters))


HookFilter.RESULT_IS_NULL = HookFilter(lambda param: param.getResult() is None)
HookFilter.RESULT_NOT_NULL = HookFilter(lambda param: param.getResult() is not None)
HookFilter.RESULT_IS_TRUE = HookFilter(lambda param: param.getResult() is True)
HookFilter.RESULT_IS_FALSE = HookFilter(lambda param: param.getResult() is False)
