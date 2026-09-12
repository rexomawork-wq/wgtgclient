# Plugin hooks and application events

Implemented against https://plugins.exteragram.app/docs/plugin-class and
https://plugins.exteragram.app/docs/xposed-hooking (SDK baseline documented as 1.4.4.3).

## Plugin API

`BasePlugin.add_hook(name, match_substring=False, priority=0)` registers for all
four named callback types. `add_on_send_message_hook(priority=0)` registers the
outgoing message callback separately.

| Callback | Replacement field |
| --- | --- |
| `pre_request_hook(name, account, request)` | `HookResult.request` |
| `post_request_hook(name, account, response, error)` | `HookResult.response` |
| `on_update_hook(name, account, update)` | `HookResult.update` |
| `on_updates_hook(name, account, updates)` | `HookResult.updates` |
| `on_send_message_hook(account, params)` | `HookResult.params` |

Higher priority runs first; ties retain registration order. Names match exactly
unless substring matching was requested. Names and accounts remain constant
through a dispatch, while replacements are passed to subsequent callbacks.
Response matching uses the original outgoing request name, even if a pre-hook
replaced the request with another class.

`DEFAULT` ignores replacement fields. Returning `None` is also a no-op.
`CANCEL` stops the operation and subsequent callbacks. `MODIFY` replaces the
object when the corresponding field is non-null. `MODIFY_FINAL` applies that same
rule and stops subsequent callbacks, even when no replacement is supplied.
RPC errors are passed unchanged; this API does not expose an error replacement
field. A response cancellation suppresses completion delivery, whereas an ordinary
RPC error still reaches the completion delegate with its null response.

Callback exceptions, `SystemExit`, and invalid strategies are logged and isolated.
In-place object mutations cannot be rolled back. Dispatch snapshots registrations
and instances, skipping instances unloaded or replaced during an earlier callback.
Public runtime operations use the engine's reentrant serialization lock.

## Java-to-Python contract

| Runtime callback | Return |
| --- | --- |
| `before_request(name, account, request)` | request or `None` to cancel |
| `after_request(name, account, response, error)` | `[cancelled: bool, response]` |
| `before_update(name, account, update)` | update or `None` to cancel |
| `before_updates(name, account, updates)` | container or `None` to cancel |
| `before_send_message(account, params)` | params or `None` to cancel |
| `on_app_event(event)` | no result |
| `shutdown()` | STOP dispatch followed by unload of all instances |

`WgtgPluginsController` validates replacement types during Java conversion and
falls back to the original object on bridge errors. Responses run on the stage
queue before either completion delegate or automatic update processing. Both
original and replacement response resources are freed after delivery/cancellation.
Container hooks run at `MessagesController.processUpdates` entry; queue replays
skip container hooks. Individual hooks run inside `processUpdateArray`, after
outer sequence bookkeeping. Short message containers use the container callback.

## Lifecycle

`on_app_event(self, event_type)` receives `AppEvent.START=0`, `STOP=1`, `PAUSE=2`,
or `RESUME=3`. Initialization loads enabled plugins, then dispatches START.
LaunchActivity forwards pause/resume. Java queues lifecycle work on the same
global queue as initialization, preserving order even while Python starts.
Consecutive duplicate events are suppressed. Newly enabled plugins receive future
events; past START/RESUME events are not replayed.

`ApplicationLoader.onTerminate()` calls `shutdownAsync()`, which emits STOP before
unloading. Android does not call `onTerminate` on production process termination;
STOP/unload therefore cannot be guaranteed when Android kills the process. Closing
the main activity does not shut down plugins used by background Telegram accounts.
No plugin callbacks execute synchronously on the activity lifecycle stack.

## Sibling module contracts

`base_plugin` re-exports `MenuItemData` and `MenuItemType` from `plugin_menus`.
It delegates registration/removal to `add_menu_item(plugin_id, data) -> str` and
`remove_menu_item(plugin_id, item_id) -> bool`. Engine cleanup calls
`clear_plugin(plugin_id)`, including after failed loads and unload exceptions.

`base_plugin` re-exports `MethodHook` and `MethodReplacement` from `hook_utils`.
`BasePlugin.hook_method(member, hook=None, priority=50, **kwargs)` delegates to
`hook_utils.hook_method(self, member, handler, priority=priority)`. Functional
`before`/`after` callbacks are wrapped in `BaseHook`; its optional filter lists
accept Python predicates. `hook_all_methods` and `hook_all_constructors` return
lists of individually registered handles. `unhook_method(handle)` delegates to
the sibling module. Engine cleanup calls `hook_utils.unhook_all(instance)` after
the unload callback, including failed load/unload paths. The sibling owns handle
tracking, idempotency, and closing old instances to further method registration.
The sibling currently does not provide the documented `HookFilter` or
`hook_filters` helpers; this change does not claim support for those helpers.

## Verification

Dedicated tests: `Tools/test_wgtg_plugin_hooks_events.py`. Run alongside existing
tests with `python3 -m unittest Tools.test_wgtg_plugin_hooks_events
Tools.test_wgtg_plugins Tools.test_wgtg_plugin_menus` (on one command line).
The shared `Tools/test_wgtg_plugins.py` is unchanged by this work.
