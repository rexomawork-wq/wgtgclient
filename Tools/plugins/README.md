# wgtg Python plugins

Install `hello_world.plugin` through wgtg settings > Plugins. Installation does
not execute it. Enable it, open its settings, then send `.hello Alice` in Saved
Messages. Expect `Hello, Alice!`. Change the template and repeat. Disable the
plugin and verify that `.hello Alice` is sent unchanged.

This is an independent, partial implementation of the documented exteraGram
API, not the exteraGram engine or complete SDK 1.4.4.3 compatibility.

Implemented: Python source .plugin/.py installation, ZIP packages with a source
entry point and YAML/JSON metadata, BasePlugin load/unload, persistent settings,
Header/Text/Input/Switch settings rows, pre_request_hook and
on_send_message_hook with DEFAULT/CANCEL/MODIFY/MODIFY_FINAL.

Not implemented: Xposed/constructor hooks, exteraGram Java classes, class proxy,
menu injection, response/update hooks, app events, full client_utils API,
Elyx resources/localization, PIP and binary dependencies. Version metadata is
retained but is not proof of compatibility. Test each plugin before relying on it.

Python code executes with the application's permissions; this is not a sandbox.
Load errors disable the plugin. A plugin interrupted during loading is skipped
at next startup. Infinite loops, native crashes and arbitrary plugin threads
cannot be safely stopped by the Python host.

Build requires Android API 24+ and Python 3.11 on the build machine. Run host tests:

```sh
python3 -B Tools/test_wgtg_plugins.py
```

Host tests do not verify Android/Chaquopy execution or a finished APK.
