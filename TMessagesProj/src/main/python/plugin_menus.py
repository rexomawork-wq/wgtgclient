"""exteraGram menu registry. See Tools/PLUGIN_MENUS.md for host contracts."""

import copy
import json
import traceback
import uuid
from dataclasses import dataclass
from enum import IntEnum
from threading import RLock
from typing import Callable, Optional

__all__ = ["MenuItemData", "MenuItemType", "add_menu_item", "remove_menu_item", "clear_plugin"]


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
    on_click: Callable[[dict], None]
    item_id: Optional[str] = None
    icon: Optional[str] = None
    subtext: Optional[str] = None
    condition: Optional[str] = None
    priority: int = 0
    visible: Optional[Callable[[dict], bool]] = None


_lock = RLock()
_items = {}  # (plugin_id, item_id) -> (registration token, copied data)


def _invalidate(tokens):
    if not tokens:
        return
    try:
        from java import jclass
    except ImportError:
        return  # Desktop tests and SDK tooling do not have Android.
    try:
        jclass("org.telegram.messenger.WgtgPluginMenus").invalidate(json.dumps(tokens))
    except (Exception, SystemExit):
        traceback.print_exc()


def add_menu_item(plugin_id, data):
    """Register an item owned by plugin_id; return its plugin-scoped string id."""
    if not isinstance(plugin_id, str) or not plugin_id:
        raise ValueError("plugin_id is required")
    data = copy.copy(data)
    data.menu_type = MenuItemType(data.menu_type)
    if not isinstance(data.text, str) or not data.text.strip():
        raise ValueError("Menu text is required")
    if not callable(data.on_click):
        raise TypeError("on_click must be callable")
    for name in ("item_id", "icon", "subtext", "condition"):
        if getattr(data, name) is not None and not isinstance(getattr(data, name), str):
            raise TypeError(name + " must be a string or None")
    data.visible = getattr(data, "visible", None)
    if data.visible is not None and not callable(data.visible):
        raise TypeError("visible must be callable or None")
    data.priority = int(data.priority)
    data.item_id = data.item_id or uuid.uuid4().hex
    with _lock:
        previous = _items.pop((plugin_id, data.item_id), None)
        _items[(plugin_id, data.item_id)] = (uuid.uuid4().hex, data)
    _invalidate([previous[0]] if previous else [])
    return data.item_id


def remove_menu_item(plugin_id, item_id):
    """Remove only this owner's item. Return whether it existed."""
    with _lock:
        previous = _items.pop((plugin_id, item_id), None)
    _invalidate([previous[0]] if previous else [])
    return previous is not None


def clear_plugin(plugin_id):
    """Call after on_plugin_unload, including failed loads; return removal count."""
    with _lock:
        keys = [key for key in _items if key[0] == plugin_id]
        tokens = [_items.pop(key)[0] for key in keys]
    _invalidate(tokens)
    return len(tokens)


def _context(value):
    if isinstance(value, dict):
        return dict(value)
    return {str(entry.getKey()): entry.getValue() for entry in value.entrySet()}


def _visible(data, context, native_context):
    try:
        if data.condition:
            from java import jclass
            if not jclass("org.telegram.messenger.WgtgPluginMenus").evaluateCondition(data.condition, native_context):
                return False
        return data.visible is None or bool(data.visible(dict(context)))
    except (Exception, SystemExit):
        traceback.print_exc()
        return False


def visible_items(menu_type, context):
    """Java bridge: JSON presentation data; callbacks and Java objects stay live."""
    values = _context(context)
    with _lock:
        snapshot = sorted(_items.items(), key=lambda item: -item[1][1].priority)
    result = []
    for key, (token, data) in snapshot:
        if data.menu_type != MenuItemType(menu_type) or not _visible(data, values, context):
            continue
        with _lock:
            if _items.get(key, (None,))[0] != token:
                continue
        result.append({"token": token, "text": data.text, "icon": data.icon,
                       "subtext": data.subtext})
    return json.dumps(result)


def click(token, context):
    """Java bridge: stale registrations and currently hidden items cannot click."""
    with _lock:
        found = next(((key, data) for key, (current, data) in _items.items() if current == token), None)
    if found is None:
        return False
    key, data = found
    values = _context(context)
    if not _visible(data, values, context):
        return False
    with _lock:
        if _items.get(key, (None,))[0] != token:
            return False
    # Never hold the registry lock while plugin code calls back into the engine.
    try:
        data.on_click(values)
        return True
    except (Exception, SystemExit):
        traceback.print_exc()
        return False
