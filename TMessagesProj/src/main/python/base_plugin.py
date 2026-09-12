from dataclasses import dataclass
from enum import IntEnum
from hook_utils import MethodHook, MethodReplacement, HookFilter, hook_filters
from plugin_menus import MenuItemData, MenuItemType

class HookStrategy(IntEnum):
    DEFAULT = 0
    CANCEL = 1
    MODIFY = 2
    MODIFY_FINAL = 3

@dataclass
class HookResult:
    strategy: HookStrategy = HookStrategy.DEFAULT
    request: object = None
    response: object = None
    update: object = None
    updates: object = None
    params: object = None

class AppEvent(IntEnum):
    START = 0
    STOP = 1
    PAUSE = 2
    RESUME = 3

class BaseHook(MethodHook):
    def __init__(self, before=None, after=None, before_filters=None, after_filters=None):
        self.before = before
        self.after = after
        self.before_filters = tuple(before_filters or ())
        self.after_filters = tuple(after_filters or ())

    def before_hooked_method(self, param):
        if self.before is not None and all(test(param) for test in self.before_filters):
            self.before(param)

    def after_hooked_method(self, param):
        if self.after is not None and all(test(param) for test in self.after_filters):
            self.after(param)

class BasePlugin:
    def on_plugin_load(self): pass
    def on_plugin_unload(self): pass
    def create_settings(self): return []
    def pre_request_hook(self, request_name, account, request): return HookResult()
    def post_request_hook(self, request_name, account, response, error): return HookResult()
    def on_update_hook(self, update_name, account, update): return HookResult()
    def on_updates_hook(self, container_name, account, updates): return HookResult()
    def on_app_event(self, event_type): pass
    def on_send_message_hook(self, account, params): return HookResult()

    def _wgtg_bind(self, plugin_id, engine):
        self.id = plugin_id
        self._engine = engine
        self._wgtg_environment = getattr(engine, "loaded", {}).get(plugin_id, {}).get("environment")
        self._wgtg_unloaded = False

    def _wgtg_check_active(self):
        if self._wgtg_unloaded:
            raise RuntimeError("Cannot register resources on an unloaded plugin instance")

    def log(self, value):
        print(f"[wgtg:{self.id}] {value}")

    def get_setting(self, key, default=None):
        return self._engine.get_setting(self.id, key, default)

    def set_setting(self, key, value, reload_settings=False):
        self._engine.set_setting(self.id, key, value)

    def export_settings(self):
        return self._engine.get_settings(self.id)

    def import_settings(self, settings, reload_settings=True):
        self._engine.replace_settings(self.id, settings)

    def add_hook(self, name, match_substring=False, priority=0):
        with self._engine._lock:
            self._wgtg_check_active()
            self._engine.add_hook(self.id, name, match_substring, priority)

    def add_on_send_message_hook(self, priority=0):
        with self._engine._lock:
            self._wgtg_check_active()
            self._engine.add_send_hook(self.id, priority)

    def add_menu_item(self, data):
        import plugin_menus
        with self._engine._lock:
            self._wgtg_check_active()
            return plugin_menus.add_menu_item(self.id, data)

    def remove_menu_item(self, item_id):
        import plugin_menus
        return plugin_menus.remove_menu_item(self.id, item_id)

    def hook_method(self, member, hook=None, priority=50, **kwargs):
        self._wgtg_check_active()
        import hook_utils
        if hook is not None and kwargs:
            raise TypeError("Use either a hook handler or functional callbacks")
        if hook is None:
            hook = BaseHook(**kwargs)
        return hook_utils.hook_method(self, member, hook, priority=priority)

    def unhook_method(self, handle):
        from hook_utils import unhook_method
        return unhook_method(handle)

    def hook_all_methods(self, clazz, name, hook=None, priority=50, **kwargs):
        return [self.hook_method(member, hook, priority, **kwargs)
                for member in clazz.getDeclaredMethods() if member.getName() == name]

    def hook_all_constructors(self, clazz, hook=None, priority=50, **kwargs):
        return [self.hook_method(member, hook, priority, **kwargs)
                for member in clazz.getDeclaredConstructors()]
