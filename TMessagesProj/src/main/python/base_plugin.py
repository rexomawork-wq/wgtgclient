from dataclasses import dataclass
from enum import IntEnum

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

class MenuItemType(IntEnum):
    MESSAGE_CONTEXT_MENU = 0
    DRAWER_MENU = 1
    MAIN_MENU = 2
    CHAT_ACTION_MENU = 3
    PROFILE_ACTION_MENU = 4

@dataclass
class MenuItemData:
    menu_type: MenuItemType
    text: str
    on_click: object
    item_id: str = None
    icon: str = None
    subtext: str = None
    condition: str = None
    priority: int = 0

class BasePlugin:
    def on_plugin_load(self): pass
    def on_plugin_unload(self): pass
    def create_settings(self): return []
    def pre_request_hook(self, request_name, account, request): return HookResult()
    def on_send_message_hook(self, account, params): return HookResult()

    def _wgtg_bind(self, plugin_id, engine):
        self.id = plugin_id
        self._engine = engine

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
        self._engine.add_hook(self.id, name, match_substring, priority)

    def add_on_send_message_hook(self, priority=0):
        self._engine.add_send_hook(self.id, priority)

    def add_menu_item(self, data):
        raise NotImplementedError("Menu injection is not supported by wgtg yet")

    def remove_menu_item(self, item_id):
        return None
