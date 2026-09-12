"""Owned in-process method hooks. Importing this module does not initialize ART hooks."""

from threading import RLock
from hook_filters import HookFilter, hook_filters, _SKIPPED

_hooks_lock = RLock()


class MethodHook:
    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        pass


class MethodReplacement(MethodHook):
    def replace_hooked_method(self, param):
        raise NotImplementedError

    def before_hooked_method(self, param):
        from java import jclass
        try:
            result = self.replace_hooked_method(param)
            if result is not _SKIPPED:
                param.setResult(result)
        except BaseException as error:
            throwable = jclass("java.lang.Throwable")
            if isinstance(error, throwable):
                param.setThrowable(error)
            else:
                param.setThrowable(jclass("java.lang.RuntimeException")(str(error)))


class _Unhook:
    def __init__(self, handles, native):
        self._handles = handles
        self._native = native

    def unhook(self):
        with _hooks_lock:
            if self._native is not None:
                self._native.unhook()
                self._native = None
                self._handles.remove(self)
                self._handles = None


def hook_method(plugin, method, hook, priority=50):
    """Hook a reflected Java Method/Constructor; return an idempotent unhook handle.

    Callbacks receive method, thisObject, args and Xposed-style get/setResult,
    get/setThrowable, hasThrowable and getResultOrThrowable methods. Higher
    priorities run first before the original and last afterwards.
    """
    from java import dynamic_proxy, jclass
    from elyx import environment_scope
    environment = getattr(plugin, "_wgtg_environment", None)
    if not callable(getattr(hook, "before_hooked_method", None)) or not callable(
            getattr(hook, "after_hooked_method", None)):
        raise TypeError("hook must implement before_hooked_method and after_hooked_method")
    if not isinstance(priority, int) or not -(2**31) <= priority < 2**31:
        raise ValueError("priority must be a Java int")
    bridge = jclass("org.telegram.messenger.WgtgMethodHooks")

    class Callback(dynamic_proxy(jclass("org.telegram.messenger.WgtgMethodHooks$Callback"))):
        def beforeHookedMethod(self, param):
            with environment_scope(environment):
                hook.before_hooked_method(param)

        def afterHookedMethod(self, param):
            with environment_scope(environment):
                hook.after_hooked_method(param)

    with _hooks_lock:
        if getattr(plugin, "_wgtg_method_hooks_closed", False):
            raise RuntimeError("Cannot register method hooks on an unloaded plugin instance")
        handles = getattr(plugin, "_wgtg_method_hooks", None)
        if handles is None:
            handles = plugin._wgtg_method_hooks = []
        handle = _Unhook(handles, bridge.hook(method, Callback(), priority))
        handles.append(handle)
        return handle


def unhook_method(handle):
    """Remove one registration. Repeated removal is harmless."""
    handle.unhook()


def unhook_all(plugin):
    """Permanently close this plugin instance and remove all its method callbacks.

    Engine must call this in finally on unload AND failed on_plugin_load.
    Already executing callbacks may finish; subsequent callbacks are disabled.
    """
    with _hooks_lock:
        plugin._wgtg_method_hooks_closed = True
        for handle in list(getattr(plugin, "_wgtg_method_hooks", ())):
            handle.unhook()


def find_class(class_name):
    from java import jclass
    try:
        return jclass(class_name).class_
    except Exception:
        return None

def _field(clazz, name):
    while clazz is not None:
        try:
            field = clazz.getDeclaredField(name)
            field.setAccessible(True)
            return field
        except Exception:
            clazz = clazz.getSuperclass()
    return None

def get_private_field(obj, field_name):
    try:
        return _field(obj.getClass(), field_name).get(obj)
    except Exception:
        return None

def set_private_field(obj, field_name, new_value):
    try:
        _field(obj.getClass(), field_name).set(obj, new_value)
        return True
    except Exception:
        return False

def get_static_private_field(clazz, field_name):
    try:
        return _field(clazz, field_name).get(None)
    except Exception:
        return None

def set_static_private_field(clazz, field_name, new_value):
    try:
        _field(clazz, field_name).set(None, new_value)
        return True
    except Exception:
        return False
