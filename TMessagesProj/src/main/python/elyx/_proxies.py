"""Lazy Java proxy generation so the data APIs also work outside Android."""
import traceback
from contextlib import nullcontext

_cache = {}


def _environment():
    from . import get_environment
    try:
        return get_environment()
    except RuntimeError:
        return None


def _scope(environment):
    from . import environment_scope
    return environment_scope(environment) if environment is not None else nullcontext()


def gen(java_class, method_name, return_value=False, default_value=None):
    if default_value is not None and not return_value:
        raise ValueError("default_value requires return_value=True")
    key = (java_class, method_name, return_value, default_value)
    if key not in _cache:
        from java import dynamic_proxy
        base = dynamic_proxy(java_class)

        def init(self, callback, *args):
            base.__init__(self)
            self.callback, self.args = callback, args
            self.environment = _environment()

        def call(self, *args):
            try:
                with _scope(self.environment):
                    result = self.callback(*args, *self.args)
                return result if return_value else None
            except Exception:
                traceback.print_exc()
                return default_value

        _cache[key] = type(method_name + "Proxy", (base,), {
            "__init__": init, method_name: call,
        })
    return _cache[key]


def gen2(java_class, return_value=False, **methods):
    from java import dynamic_proxy
    environment = _environment()

    def wrap(callback):
        def call(self, *args):
            try:
                with _scope(environment):
                    result = callback(*args)
                return result if return_value else None
            except Exception:
                traceback.print_exc()
                return None
        return call

    return type("MultiMethodProxy", (dynamic_proxy(java_class),), {
        name: wrap(callback) for name, callback in methods.items()
    })


def proxy(name):
    from java import jclass
    interfaces = {
        "Runnable": ("java.lang.Runnable", "run", False),
        "OnClickListener": ("android.view.View$OnClickListener", "onClick", False),
        "Callback": ("org.telegram.messenger.Utilities$Callback", "run", False),
        "Callback2": ("org.telegram.messenger.Utilities$Callback2", "run", False),
        "Callback3": ("org.telegram.messenger.Utilities$Callback3", "run", False),
        "CallbackReturn": ("org.telegram.messenger.Utilities$CallbackReturn", "run", True),
    }
    interface, method, returns = interfaces[name]
    return gen(jclass(interface), method, returns)
