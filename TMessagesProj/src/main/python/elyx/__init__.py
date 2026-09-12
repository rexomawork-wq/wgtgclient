"""Documented Elyx data and callback APIs; see PLUGIN_COMPATIBILITY.md."""
import importlib
import inspect
from contextlib import contextmanager
from contextvars import ContextVar

from ._assets import Asset, Assets, AssetNotFoundException, AssetsDirNotFoundException
from ._strings import Strings
from ._proxies import gen, gen2, proxy

_current = ContextVar("elyx_environment", default=None)
_environments = {}


def register_environment(package, environment):
    """Host API: bind a unique module namespace to its plugin environment."""
    if not package or not isinstance(environment, dict):
        raise ValueError("A module namespace and environment dict are required")
    _environments[package] = environment


def unregister_environment(package):
    _environments.pop(package, None)


@contextmanager
def environment_scope(environment):
    """Host API for imports/callbacks without a namespaced Python caller."""
    token = _current.set(environment)
    try:
        yield environment
    finally:
        _current.reset(token)


def get_environment():
    current = _current.get()
    if current is not None:
        return current
    frame = inspect.currentframe()
    try:
        frame = frame.f_back
        while frame:
            name = frame.f_globals.get("__name__", "")
            for package in sorted(_environments, key=len, reverse=True):
                if name == package or name.startswith(package + "."):
                    return _environments[package]
            frame = frame.f_back
    finally:
        del frame
    raise RuntimeError("Not called from a registered Elyx plugin")


def import_module(name, package=None):
    if name.startswith("."):
        return importlib.import_module(name, package or get_environment().get("package"))
    try:
        namespace = get_environment().get("package")
    except RuntimeError:
        namespace = None
    if namespace:
        local = namespace + "." + name
        try:
            return importlib.import_module(local)
        except ModuleNotFoundError as exc:
            # A local module's missing dependency must not trigger global fallback.
            if exc.name != local and not local.startswith(exc.name + "."):
                raise
    return importlib.import_module(name, package)


class LazyDict(dict):
    def __getattr__(self, name):
        return self[name]


class SettingsController:
    def __init__(self, plugin_id):
        self.plugin_id = plugin_id

    def _backend(self):
        environment = self._environment()
        return environment["settings_backend"]

    def _environment(self):
        try:
            environment = get_environment()
        except RuntimeError:
            environment = None
        if environment is not None and environment.get("plugin_id") == self.plugin_id:
            return environment
        for environment in _environments.values():
            if environment.get("plugin_id") == self.plugin_id:
                return environment
        raise RuntimeError("Settings requested for an unregistered Elyx plugin")

    def get_settings(self):
        return dict(self._backend().get_settings(self.plugin_id))

    def get_setting(self, key, default=None):
        return self._backend().get_setting(self.plugin_id, key, default)

    def set_setting(self, key, value, reload_settings=False):
        self._backend().set_setting(self.plugin_id, key, value)
        if reload_settings:
            callback = self._environment().get("reload_settings")
            if callback:
                callback()

    def clear_settings(self):
        self._backend().replace_settings(self.plugin_id, {})

    get = get_setting
    set = set_setting
    __call__ = get_setting
    __getitem__ = get_setting
    __setitem__ = set_setting


def mvel_execute(script, data, to_type=None, java_instance=None):
    from java import jclass, cast
    if not isinstance(script, str):
        raise TypeError("MVEL script must be a string")
    HashMap = jclass("java.util.HashMap")
    if isinstance(data, dict):
        values = HashMap()
        for key, value in data.items():
            values.put(key, value)
    elif isinstance(data, HashMap):
        values = data
    else:
        raise TypeError("MVEL data must be a Python dict or java.util.HashMap")
    # Match the menu bridge's interpreted evaluation: no generated JVM bytecode
    # optimizer, which Android cannot load as desktop JVM classes.
    mvel = jclass("org.mvel2.MVEL")
    context = cast("java.lang.Object", java_instance)
    values = cast("java.util.Map", values)
    if to_type is not None:
        return mvel.eval(script, context, values, to_type)
    return mvel.eval(script, context, values)


def __getattr__(name):
    if name in ("assets", "strings", "settings", "metainfo", "refmap"):
        environment = get_environment()
        if name not in environment:
            raise AttributeError(f"Plugin has no Elyx {name}")
        return environment[name]
    if name in ("Runnable", "OnClickListener", "Callback", "Callback2", "Callback3", "CallbackReturn"):
        return proxy(name)
    raise AttributeError(name)


__all__ = ("Asset", "Assets", "AssetNotFoundException", "AssetsDirNotFoundException",
           "Strings", "SettingsController", "LazyDict", "get_environment", "import_module",
           "gen", "gen2", "Runnable", "OnClickListener", "Callback", "Callback2",
           "Callback3", "CallbackReturn", "mvel_execute")
