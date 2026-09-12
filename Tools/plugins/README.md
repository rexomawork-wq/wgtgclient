# wgtg Python plugins

Install `hello_world.plugin` through wgtg settings > Plugins. Installation does
not execute it. Enable it, open its settings, then send `.hello Alice` in Saved
Messages. Expect `Hello, Alice!`. Change the template and repeat. Disable the
plugin and verify that `.hello Alice` is sent unchanged.

This is an independent, partial implementation of the documented exteraGram
API, not the exteraGram engine or complete SDK 1.4.4.3 compatibility.

Implemented: Python source .plugin/.py installation, ZIP packages with a source
entry point and YAML/JSON metadata, BasePlugin load/unload, persistent settings,
Header/Text/Input/Switch/Selector/Divider/EditText settings rows, pre_request_hook and
on_send_message_hook with DEFAULT/CANCEL/MODIFY/MODIFY_FINAL.

Not implemented: exteraGram-specific Java classes, class proxy, the full
client_utils API and arbitrary binary dependencies. See the linked runtime
documents below for hooking, menus, events and dependency support. Version
metadata is retained but is not proof of compatibility. Test each plugin before
relying on it.

Python code executes with the application's permissions; this is not a sandbox.
Load errors disable the plugin. A plugin interrupted during loading is skipped
at next startup. Infinite loops, native crashes and arbitrary plugin threads
cannot be safely stopped by the Python host.

Build requires Android API 24+ and Python 3.11 on the build machine. Run host tests:

```sh
python3 -B Tools/test_wgtg_plugins.py
```

Host tests do not verify Android/Chaquopy execution or a finished APK.

## Focused Settings And Import Support

Settings signatures were checked against
https://plugins.exteragram.app/docs/plugin-settings. Selector values are persisted
zero-based indices, with an Android choice dialog. Switch, Input and EditText
persist values before calling `on_change(value)`; text inputs use an explicit Save
action. Text click and long-click handlers receive the Android view. Text rows can
open a nested page through `create_sub_fragment`. Opening another page expires the
previous page's callback tokens; disabling a plugin invalidates its callbacks.
Header, Divider, text accent/red colors, drawable icons, multiline inputs and
maximum input length are rendered. Settings and plugin cards use host theme colors.

This remains partial compatibility: Custom/SimpleSettingFactory, EditText masks,
link_alias deeplinks, automatic `reload_settings` refresh and nested-page back
navigation are not implemented. Masks fail explicitly when building the page.
Unknown setting types also fail explicitly instead of appearing as inert text.

Packaged plugin directories remain on `sys.path` through constructors, load hooks,
settings callbacks and other delayed imports, until unload. Modules imported from
that directory are removed on unload/update or failed load. The interpreter is
shared: absolute sibling imports with identical names can resolve to another
plugin's module. Prefer package-relative imports, which use each plugin's unique
package name. Loose legacy plugins share the root directory and do not
have the same sibling-module cleanup guarantees.

Related runtime support and limits are described in `../PLUGIN_HOOKS_EVENTS.md`,
`../PLUGIN_MENUS.md`, `../PLUGIN_DEPENDENCIES_RUNTIME.md` and `../METHOD_HOOKS.md`.
