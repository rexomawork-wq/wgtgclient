# Verified Compatibility Scope

Research checked 2026-09-12 against the public exteraGram SDK documentation
(SDK baseline 1.4.4.3, Python 3.11). This host implements the subset below;
it does not claim full Elyx or exteraGram compatibility.

Sources:

- https://plugins.exteragram.app/docs/elyx/public-api
- https://plugins.exteragram.app/docs/elyx/project-structure
- https://plugins.exteragram.app/docs/elyx/metadata
- https://plugins.exteragram.app/docs/elyx/assets
- https://plugins.exteragram.app/docs/elyx/localization
- https://plugins.exteragram.app/docs/elyx/settings
- https://plugins.exteragram.app/docs/elyx/dependencies
- https://plugins.exteragram.app/docs/pip
- https://plugins.exteragram.app/docs/android-utils
- https://plugins.exteragram.app/docs/client-utils
- https://plugins.exteragram.app/docs/alert-dialog-builder
- https://plugins.exteragram.app/docs/bulletin-helper

## Runtime Integration Contract

`plugin_dependencies` does not modify `sys.path`, import plugin/dependency code,
invoke pip/subprocesses, or alter runtime/controller state. The engine now calls
it during install/enable, persists dependency generations, and activates them
before localization or entry-module code executes. Chaquopy's pip block already
includes `packaging==24.2` and PyYAML. Gradle also includes MVEL 2.5.2.Final.

The integrated engine uses this contract on its installation/enable path:

```python
from plugin_dependencies import (
    validate_required_plugins, prepare_requirements, install_requirements,
)

validate_required_plugins(metadata.get("requires"), installed_plugins)
plan = prepare_requirements(
    metadata.get("requirements"),
    wheels=direct_bundled_wheel_paths,
)
paths = install_requirements(plan, new_plugin_generation_site_directory)
# Runtime activates paths before importing the entry module.
```

- `requirements`: `None`, a list/tuple of PEP 508 strings (single-file
  `__requirements__`), or an Elyx comma-separated string. Version-range commas
  and extras are accepted. Prefer lists for complex quoted marker expressions.
- `requires`: a mapping of `plugin_id` or `plugin_id (minimum_version)` to a
  descriptive download link. `installed_plugins` is a mapping of id to
  `{"version": "2.1", "enabled": True}`. Missing/disabled/outdated plugins fail.
  Links are never fetched; this is not a Python requirement list.
- `wheels`: iterable of filesystem paths to direct `.whl` children selected by
  the runtime from the validated `refmap.wheels` directory. Bundled versions
  are pinned and participate in transitive resolution. The runtime owns refmap
  path validation and archive/plugin extraction.
- `prepare_requirements` returns an immutable `DependencyPlan` containing
  downloaded/validated wheel bytes. Optional `fetch(url) -> bytes` supports
  offline index fixtures; production defaults to HTTPS PyPI JSON and artifact
  downloads. Optional `environment` overrides PEP 508 marker values only,
  not interpreter/wheel compatibility. Default markers describe this Python
  on Android/Linux; platform release/version are empty, device architecture
  comes from Python. No dependency module executes during preparation.
- `install_requirements(plan, target_dir)` requires a **new**, app-private,
  plugin-specific generation directory and returns a tuple of absolute import
  paths (empty for an empty plan). It validates again, extracts into a sibling
  staging directory, then renames. Failure removes the staging directory.
  Existing targets are rejected. Caller must serialize installation/activation
  for a target; the rename is not a cross-process lock.
- Catch `DependencyError` for validation/resolution failures. Network, malformed
  ZIP/index metadata, and filesystem failures can also raise their standard
  Python exceptions: report these as installation failures and do not import
  the plugin. No partial target is published after an extraction failure.
- Runtime implements activation, generation cleanup, unload/uninstall, and
  installation rollback. Dependency downloads run outside its hook lock.
  `sys.path` is process-wide: plugin-specific directories do **not** isolate
  `sys.modules`. Runtime rejects colliding active or host-library top-level
  modules, even if the versions agree, rather than replacing running modules.

## Installer Support And Limits

Real pure-Python wheel installation uses stdlib HTTPS/ZIP/file APIs and
`packaging` for version/specifier/marker/wheel parsing. Resolution backtracks
over stable versions with transitive `Requires-Dist` and extras, checks
`Requires-Python`, skips yanked releases, and verifies PyPI's SHA-256 digest.
Explicitly constrained prereleases follow `packaging` semantics.

Only compatible `py3`/current `pyXY`, `none`, `any` wheels with Wheel-Version 1.0
and Root-Is-Purelib true are supported. Source builds, editable/VCS/direct URL
requirements, arbitrary `===` versions, CLI entry-point installation, binary
extensions (including Android-specific wheels), and `.data` schemes other than
purelib/platlib are rejected. `.pth` startup hooks and precompiled bytecode are
rejected. Purelib/platlib data files are relocated into the returned import root.
Wheel metadata stays available for `importlib.metadata`.

Archive validation rejects absolute/traversing/backslash/drive paths, symlinks,
special files, duplicate destinations, colliding dependency files, native file
extensions and common executable magic bytes. Downloaded wheels are limited to
32 MiB; each expanded wheel to 128 MiB / 10,000 entries. Resolution is bounded
to 500 states / 100 distributions. Wheel RECORD files are preserved, not used
as trust signatures or rewritten after data relocation. Bundled artifacts have
no external digest authority; trust comes from the plugin distribution.
These checks do not sandbox Python code or guarantee an arbitrary pure-Python
package will run on Android. Packages requiring OS facilities may still fail.

No persistent download cache or shared-library deduplication is implemented.
The runtime reuses installed generations on offline enable/restart, removes
obsolete generations after updates/uninstall, and collects orphan generations
on startup. Each newly prepared generation is resolved and installed afresh,
including changed bundled wheels with unchanged filenames.

## Elyx Surface

Implemented documented symbols: `Assets`, `Asset`, both asset exceptions,
`Strings`, `SettingsController`, `LazyDict`, `get_environment`, `import_module`,
`gen`, `gen2`, `Runnable`, `OnClickListener`, `Callback`, `Callback2`, `Callback3`,
`CallbackReturn`, and `mvel_execute`. Every symbol in the documented public
`elyx.__all__` now has an implementation; behavioral limits are listed below.

- Assets support named/full/nested lookup, normalized stems, iteration,
  containment, parent, content bytes/text/JSON/safe YAML, `from_path`, Java file,
  `to_drawable`, `to_image_location`, bitmap drawables, SVG drawables/bitmaps/
  themed thumbnails, Lottie drawables, and temporary HTTP(S) downloads.
  Bitmap/SVG dimensions are pixels; Lottie dimensions use Android density.
  Downloads use a 30-second timeout and 32 MiB limit, with cleanup on failure.
  Temporary downloads and Lottie decoder copies live in app cache and remain
  until app-cache cleanup. Lottie receives a copy because Telegram can delete
  invalid input; bundled originals are protected. Ambiguous stems fail explicitly.
  Asset lookup rejects traversal and escaping symlinks. Parent is intentionally
  available per docs; this API is not a filesystem sandbox.
- Strings support locale/English/key fallback, defaults, callable formatting,
  explicit locales, copied mutable values, and the documented three-form
  Slavic plural helper. An explicit environment `locale` is honored, otherwise
  Android system locale is used (English outside Android). This host does not
  yet offer upstream Elyx's dedicated language-selection UI.
- Proxy callbacks append constructor arguments, catch/log exceptions, and
  preserve return/default values as documented. Java interfaces are resolved
  lazily. Proxies capture their creation environment for callbacks implemented
  in shared libraries, restoring the previous scope afterward. `gen2` logs
  failures and returns None on failure.
- `mvel_execute` calls `org.mvel2.MVEL.eval` through Chaquopy, accepts Python
  dictionaries or Java HashMaps, and supports `to_type` and `java_instance`
  (`this`). Errors propagate to the caller. Like the Java menu bridge, it uses
  interpreted evaluation to avoid desktop JVM bytecode generation on Android.
  This differs from upstream's compiled-expression cache: no compiled cache is
  claimed. MVEL can call Java methods; it is not a sandbox.

The engine now registers an environment before loading localization and the
entry module, scopes constructors/load/unload/events/hooks/settings callbacks,
and unregisters it after unload resource cleanup. Failed localization or entry
imports remove the environment, namespaced modules, and active dependency paths
using the same existing failure handling as other plugin load failures.

Refmaps are discovered in documented YAML/YML/JSON order. Assets default to a
root `assets` directory; explicitly mapped assets/strings must resolve inside
the plugin, exist, and cannot escape via symlinks. `strings` accepts a file or
directory of direct YAML/YML/JSON/Python files. Locale comes from the final
underscore suffix, or English when absent. Python catalogs execute only on
load, after dependencies activate, in modules within the plugin namespace;
public simple values are collected. Empty catalogs leave `strings` absent.
Metadata description placeholders resolve through locale/English/key fallback
and are used by the loaded plugin and plugin list. JSON/YAML metadata and
explicit Python metadata files are supported; Python metadata uses literal
assignments (plain or double-underscore keys), without executing code during
inspection. Dynamically computed Python metadata is outside this verified scope.

Host-only integration functions used by the engine (not upstream public API):

```python
import elyx

environment = {
    "plugin_id": plugin_id,
    "package": unique_module_namespace,
    "settings_backend": engine,
    "settings": elyx.SettingsController(plugin_id),
    "metainfo": metadata,
    "refmap": refmap,
    # optional: assets=Assets(path), strings=Strings(catalog), locale="en",
    # optional: reload_settings=zero_argument_callback
}
elyx.register_environment(unique_module_namespace, environment)
with elyx.environment_scope(environment):
    load_entry_module()
# On unload, after callbacks are detached:
elyx.unregister_environment(unique_module_namespace)
```

Caller-stack namespace lookup supports plugin functions/callbacks after import;
explicit context scopes support host-driven entry/callbacks and restore prior
context on exit. Missing environment raises RuntimeError; missing optional
environment values raise AttributeError. Dynamic values are excluded from
`__all__`. The engine uses `wgtg_plugin_<id>` and removes that package and its
submodules on unload/reload. Relative imports and `elyx.import_module` stay
within this namespace. Full upstream automatic namespacing of bare imports is
not implemented; a bare `import helper` may still use Python's global cache.
Local imports through `elyx.import_module` fall
back globally only if the local module is absent, not when its own dependency
is missing. The backend methods are `get_settings(id)`,
`get_setting(id, key, default)`, `set_setting(id, key, value)`, and
`replace_settings(id, dict)`, all implemented by the serialized engine and
persisted in the same settings store as BasePlugin. Explicit controllers can
resolve an active plugin by id for shared-library callers. Settings are available
during import and unload and persist across reloads. `reload_settings=True`
invokes an environment callback if supplied; the current host has no dedicated
open-settings-screen refresh bridge, so that optional UI refresh remains absent.

## Android And UI Surface

- `android_utils`: `R`, click/long-click listeners, UI scheduling, clipboard
  copying with a copied bulletin. `log` uses FileLog text output; the upstream
  Java object inspector is not present.
- `client_utils`: documented controller/account getters, fragment lookup,
  queue names/scheduling, request callback/delegate, notification delegate,
  `send_message` dictionary and `send_text` for plain text/raw Java fields.
  `get_notifications_settings` returns account SharedPreferences. Existing
  `messages_controller` / `send_messages_helper` aliases are retained.
  The host has a dedicated Python-created DispatchQueue for PLUGINS_QUEUE.
- Optional accounts use the event account inside request, response, update and
  send hooks, and otherwise default to the selected UI account. Nested scopes
  restore the caller's account, including on failure. Requests and queue callbacks
  preserve the selected/explicit account. `send_message` captures account before posting.
  Media preparation, editing, parse modes and integer reply-message lookup are
  not implemented. Pass actual Java objects for complex SendMessageParams fields.
- `ui.alert.AlertDialogBuilder`: title/message/view/items, three buttons,
  dismiss/cancel listeners, create/show/dismiss/get_dialog/get_button,
  cancelability, outside-touch behavior and progress. Chainable setters and
  builder-first callbacks match docs. Caller must use the UI thread. Advanced
  appearance/animation/back-button methods are not implemented.
- `ui.bulletin.BulletinHelper`: info/error/success/simple/two-line/button/copied
  bulletins and duration constants, automatically posted on the UI thread.
  Uses explicit/current fragment then global factory; the separate upstream
  bottom-sheet selection logic, undo and other contextual helpers are absent.
- Existing `ui.settings` rows are unchanged. Elyx is integrated with the engine's
  current renderer, hooks and dependency lifecycle; this does not imply support
  for additional upstream row types or every upstream UI helper.

## Validation

From repository root:

```sh
python3 -m unittest discover -s TMessagesProj/src/test/python -p 'test_elyx_utilities.py' -v
python3 -m unittest discover -s TMessagesProj/src/test/python -p 'test_elyx_runtime.py' -v
python3 -m unittest discover -s TMessagesProj/src/test/python -p 'test_elyx_assets.py' -v
python3 -m unittest Tools.test_wgtg_dependency_runtime Tools.test_wgtg_plugin_hooks_events -v
python3 -m unittest discover -s TMessagesProj/src/test/python -p 'test_plugin_dependencies.py' -v
PLUGIN_DEPENDENCIES_LIVE=1 python3 -m unittest discover -s TMessagesProj/src/test/python -p 'test_plugin_dependencies.py' -v
```

Tests exercise actual ZIP extraction and import, local bundled wheels, a
recorded PyPI-shaped index, dependency backtracking/conflicts/markers/extras,
hash tampering, traversal/symlink/binary/startup-file rejection, atomic cleanup,
plugin requirements, environment isolation, assets/strings/settings, and Java
wrapper calls using a recording bridge. The opt-in live test downloads and
installs requests 2.32.3, tinydb 4.8.2 and transitive dependencies from PyPI,
imports from the new site directory and exercises TinyDB in-memory storage.
Java signatures/resources were checked against this repository; desktop bridge
tests do not replace Chaquopy/device UI and lifecycle testing.

Elyx runtime tests exercise real entry modules, local imports, JSON/YAML/Python
catalogs, metadata interpolation, settings persistence, nested callback scopes,
two-plugin isolation, failure cleanup, reload, and localization importing an
actual installed bundled wheel. Asset tests download over a local HTTP server
and verify Java conversion signatures, density handling and protection against
the Lottie decoder deleting the original resource.

The MVEL tests invoke the actual Python adapter against MVEL 2.5.2.Final on a
host JVM through a small JPype bridge (no simulated expression evaluator):

```sh
ELYX_TEST_MVEL_JAR=/path/to/mvel2-2.5.2.Final.jar python3 -m unittest discover -s TMessagesProj/src/test/python -p 'test_elyx_mvel.py' -v
```

This opt-in test requires JPype1 and a host JVM. It checks values, Java HashMaps,
`this`, result conversion, and errors. Android rendering, native Lottie decoding,
and Chaquopy overload conversion still require device verification.
