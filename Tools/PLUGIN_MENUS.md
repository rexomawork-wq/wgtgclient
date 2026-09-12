# Plugin Menu Integration Contract

Reference checked: https://plugins.exteragram.app/docs/plugin-class#menu-items
(SDK documentation baseline 1.4.4.3, checked 2026-09-12). The documented
MenuItemData fields, in order, are menu_type, text, on_click, item_id=None,
icon=None, subtext=None, condition=None, priority=0. condition is an MVEL
expression, not Python eval. This implementation additionally accepts
visible=None, a Python callable receiving the same dictionary as on_click.
Both predicates must pass. Predicate exceptions hide the row; click exceptions
are logged and isolated. Predicates run again at click time.

## Required BasePlugin And Runtime Wiring

These files are owned by the runtime agent and were not edited by the menu
implementation. The runtime agent has now added the forwarding and cleanup in the
shared worktree; the lifecycle tests exercise that integration. The exact contract
is preserved below for review.

In base_plugin.py replace the local menu declarations with:

```python
from plugin_menus import MenuItemData, MenuItemType
import plugin_menus

# Inside BasePlugin:
def add_menu_item(self, data):
    return plugin_menus.add_menu_item(self.id, data)

def remove_menu_item(self, item_id):
    return plugin_menus.remove_menu_item(self.id, item_id)
```

Runtime contract: call plugin_menus.clear_plugin(plugin_id) in the finally path
after on_plugin_unload, even if the plugin callback throws. Run this for disable,
uninstall, replacement, initialization/shutdown, and failed load (including a
failure in __init__ or on_plugin_load). Cleanup must still run if no live instance
exists. Failed loads must not bypass this cleanup. Clear after the callback so
any items registered by that callback are also removed. No Java controller menu
methods are required. The bridge uses WgtgPluginsController.isReady() and imports
plugin_menus directly through Chaquopy.

Public Python signatures:

```python
add_menu_item(plugin_id: str, data: MenuItemData) -> str
remove_menu_item(plugin_id: str, item_id: str) -> bool
clear_plugin(plugin_id: str) -> int
```

IDs are scoped to the plugin. Missing/empty item_id generates a UUID; the return
value is the id to pass to remove_menu_item. Re-registering the same id replaces
the registration and invalidates existing native rows. Removing another owner's
id has no effect. clear_plugin is idempotent and returns the number removed.
Data is copied on registration; use re-registration to update it. Larger
priorities appear first within the appended plugin block; equal priorities
preserve registration order. Native menu ordering is preserved.

Registration/removal may run on background threads. Native menus invoke Python
visibility and click callbacks synchronously on the UI thread; keep callbacks
short and schedule blocking work yourself. Registry locks never span plugin code
or engine calls. Unload invalidates future dispatch; a callback already in flight
is not interrupted. Plugins must stop any workers that could register new items
after unload. Native rows are removed on the UI thread, and stale registration
tokens cannot invoke removed or replacement callbacks. The static native tracking
map holds weak view references, not plugin callbacks or fragments.

## Native Surfaces And Context

The integer values below preserve the existing wgtg SDK enum values. The public
documentation specifies the symbolic names, without a numeric wire contract.

| Type | Value | Native integration |
| --- | --- | --- |
| MESSAGE_CONTEXT_MENU | 0 | ChatActivity's normal message popup, after native actions |
| DRAWER_MENU | 1 | LaunchActivity.addPluginDrawerItems, in the dialogs overflow beside side-menu bots |
| MAIN_MENU | 2 | DialogsActivity's main overflow, excluding archive/community menus |
| CHAT_ACTION_MENU | 3 | ChatActivity header overflow, refreshed on every open |
| PROFILE_ACTION_MENU | 4 | ProfileActivity overflow, refreshed on every open |

MESSAGE_CONTEXT_MENU applies where ChatActivity actually builds its message popup;
special actions which navigate directly or suppress that popup keep existing
behavior. Chat/profile entries likewise appear where the native overflow exists.
This branch removed the sliding drawer and its adapter; DrawerLayoutContainer is
only a content container. The existing dialogs overflow hosts the former drawer
actions and show_in_side_menu bots. LaunchActivity.addPluginDrawerItems adds real
native rows to that surface on every open, before those bots. DRAWER_MENU and
MAIN_MENU retain separate registrations and priority blocks, each rendered once.
Archive/community menus do not receive either block. No new sliding drawer is
introduced. The existing pause/resume event hooks in LaunchActivity are preserved.

All callbacks receive a fresh Python dict containing these keys. Java values are
live objects, never JSON copies. Unavailable objects are None; unavailable ids are
0. The account is the hosting fragment's account, not UserConfig.selectedAccount.

| Key | Value |
| --- | --- |
| account | Fragment account index |
| context | Fragment's Android Context; LaunchActivity for DRAWER_MENU |
| fragment | Hosting ChatActivity, ProfileActivity, or DialogsActivity |
| dialog_id | Screen dialog id, 0 for main/drawer menus; retains encrypted dialog encoding |
| user, userId, userFull | Screen peer user, positive user id, available UserFull |
| chat, chatId, chatFull | Screen peer chat, positive chat id, available ChatFull |
| encryptedChat | Screen's EncryptedChat, if applicable |
| message | Clicked MessageObject for message menu, None otherwise |
| groupedMessages | Clicked message's GroupedMessages, if available |
| botInfo | ChatActivity's LongSparseArray of BotInfo; profile UserFull.bot_info when available |

The message and group are captured when the popup opens, before dismissal clears
ChatActivity.selectedObject. Their identities are preserved when the handler runs.
Other peer values also represent the screen at menu creation. Main/drawer peer,
message and bot fields are None/0. Drawer fragment is the originating DialogsActivity,
and account comes from that fragment. Clicks are rejected if its account changed
or the fragment finished after opening the menu. Changing dictionary entries inside one
visibility callback does not change another callback's dictionary.

## Bridge And Validation

WgtgPluginMenus.context builds the map. populate(ActionBarMenuItem, type,
fragment, context) runs in onShowSubMenu, after lazy native rows are materialized.
append(ActionBarPopupWindowLayout, type, fragment, context, dismiss) and
append(ItemOptions, type, fragment, context) add real ActionBarMenuSubItem views
with drawable-name resolution, title, subtext and native dismissal.

Internal Python bridge functions are visible_items(type, java_map) -> JSON and
click(registration_token, java_map) -> bool. JSON contains only presentation data
and tokens; callbacks remain in Python. Java invalidate(tokens_json) removes rows
after Python removal. MVEL expressions use evaluateCondition(expression, java_map)
and org.mvel:mvel2:2.5.2.Final, added to TMessagesProj/build.gradle.
The published JAR contains the exact evalToBoolean(String, Map<String, Object>)
overload used here. Host tests compile the actual WgtgPluginMenus class against
that artifact with Java 8 compatibility and exercise true/false expressions,
Java fields/methods, long ids, null short-circuiting, assignments and invalid syntax.
The map copy prevents expressions from replacing entries in the click snapshot.

Run registry and runtime lifecycle tests with:

```sh
python3 -m unittest Tools.test_wgtg_plugin_menus -v
```

The optional native bridge host test uses Android/Chaquopy doubles, the published
MVEL JAR, and com.vaadin.external.google:android-json:0.0.20131108.vaadin1 (the
Android JSON implementation). Modern org.json has different optString(null)
behavior and would miss the regression which displayed a literal "null" subtitle.
The bridge explicitly checks isNull for optional icon/subtext fields.

```sh
WGTG_TEST_JDK=/path/to/jdk \
WGTG_TEST_MVEL_JAR=/path/to/mvel2-2.5.2.Final.jar \
WGTG_TEST_JSON_JAR=/path/to/android-json-0.0.20131108.vaadin1.jar \
python3 -m unittest Tools.test_wgtg_plugin_menu_bridge -v
```

The host test exercises native row creation, icon/subtitle presentation, drawer
type dispatch and live context, dismissal, account/fragment lifecycle guards,
queued UI cleanup, listener removal, and rebuilding without duplicate rows or
removing existing native actions. It does not substitute for device rendering tests.

Device validation still needs opening each menu, toggling visibility between
opens/clicks, inspecting account/peer/message context, and disabling a plugin with
a popup open. Full Gradle validation remains blocked: the default Java 17
installation lacks javac, and the alternate Java 21 runtime (whose compiler runs
the host test) fails Gradle with missing sun.reflect.ReflectionFactory. Neither
attempt reaches app compilation.
